"""Time Tracking Discord Notifications: private timer receiver and durable Discord scheduler.

stdlib only; intended for private LAN/Tailscale usage, not unauthenticated public exposure.
"""
import hmac
import json
import logging
import os
import sqlite3
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

DB_PATH = os.getenv("DB_PATH", "/data/timetracking.sqlite3")
API_KEY = os.environ.get("API_KEY", "")
DISCORD_WEBHOOK = os.environ.get("DISCORD_WEBHOOK", "")
PORT = int(os.getenv("PORT", "8787"))
POLL_SECONDS = max(3, int(os.getenv("POLL_SECONDS", "10")))
LOCK = threading.RLock()
log = logging.getLogger("time-tracking-discord")


def connect():
    db = sqlite3.connect(DB_PATH, timeout=15)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout=15000")
    return db


def init_db():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    with connect() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS timers (
            account TEXT NOT NULL, id TEXT NOT NULL, label TEXT NOT NULL,
            message TEXT NOT NULL DEFAULT '',
            ready_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0, retry_after INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (account, id)
        )""")
        columns = {row[1] for row in db.execute('PRAGMA table_info(timers)')}
        if 'message' not in columns:
            db.execute("ALTER TABLE timers ADD COLUMN message TEXT NOT NULL DEFAULT ''")


def apply_snapshot(payload):
    if not isinstance(payload, dict) or not isinstance(payload.get('account'), str):
        raise ValueError('Invalid account')
    account = payload['account'].strip()
    if not account or len(account) > 32:
        raise ValueError('Invalid account length')
    timers = payload.get('timers')
    if not isinstance(timers, list) or len(timers) > 30:
        raise ValueError('Invalid timers list')
    now = int(time.time())
    normalized = []
    seen = set()
    for timer in timers:
        if not isinstance(timer, dict):
            raise ValueError('Invalid timer')
        ident, label, ready_at = timer.get('id'), timer.get('label'), timer.get('ready_at')
        message = timer.get('message', '')
        if (not isinstance(ident, str) or not 1 <= len(ident) <= 64 or
            not all(c.isalnum() or c in '_-' for c in ident) or ident in seen):
            raise ValueError('Invalid/duplicate timer id')
        if not isinstance(label, str) or not 1 <= len(label) <= 100:
            raise ValueError('Invalid timer label')
        if not isinstance(message, str) or len(message) > 200:
            raise ValueError('Invalid timer message')
        if isinstance(ready_at, bool) or not isinstance(ready_at, int) or not now < ready_at <= now + 366*86400:
            raise ValueError('Invalid ready_at')
        seen.add(ident)
        normalized.append((ident, label, message, ready_at))

    with LOCK, connect() as db:
        # A full snapshot: clear removed/harvested categories, including previously fired ones.
        if seen:
            db.execute('DELETE FROM timers WHERE account=? AND id NOT IN (' +
                       ','.join('?' for _ in seen) + ')', (account, *seen))
        else:
            db.execute('DELETE FROM timers WHERE account=?', (account,))
        for ident, label, message, ready_at in normalized:
            db.execute("""INSERT INTO timers (account,id,label,message,ready_at,updated_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(account,id) DO UPDATE SET
                label=excluded.label, message=excluded.message, ready_at=excluded.ready_at, updated_at=excluded.updated_at,
                status=CASE WHEN timers.ready_at <> excluded.ready_at THEN 'pending' ELSE timers.status END,
                attempts=CASE WHEN timers.ready_at <> excluded.ready_at THEN 0 ELSE timers.attempts END,
                retry_after=CASE WHEN timers.ready_at <> excluded.ready_at THEN 0 ELSE timers.retry_after END
            """, (account, ident, label, message, ready_at, now))
    return len(normalized)


def deliver(webhook, account, label, message, ready_at):
    stamp = datetime.fromtimestamp(ready_at, tz=timezone.utc).strftime('%Y-%m-%d %H:%M UTC')
    text = message or (label + ' is ready.')
    body = json.dumps({
        'username': 'Time Tracking',
        'allowed_mentions': {'parse': []},
        'embeds': [{
            'title': label.replace('*', '') + ' ready!',
            'description': text.replace('*', '') + '\n**' + account.replace('*', '') + '**',
            'color': 5763719,
            'footer': {'text': 'RuneLite Time Tracking estimate • ' + stamp}
        }]
    }).encode('utf-8')
    req = Request(webhook, body, headers={'Content-Type': 'application/json', 'User-Agent': 'TimeTrackingDiscord/1.0'}, method='POST')
    with urlopen(req, timeout=15) as resp:
        return 200 <= resp.status < 300


def process_due(deliver_fn=None):
    deliver_fn = deliver_fn or (lambda account, label, message, ready: deliver(DISCORD_WEBHOOK, account, label, message, ready))
    now = int(time.time())
    with LOCK, connect() as db:
        due = db.execute("""SELECT account,id,label,message,ready_at,attempts FROM timers
            WHERE status='pending' AND ready_at<=? AND retry_after<=?
            ORDER BY ready_at LIMIT 20""", (now, now)).fetchall()
        for row in due:
            account, ident, label, message, ready_at, attempts = [row[k] for k in ('account','id','label','message','ready_at','attempts')]
            try:
                if not deliver_fn(account, label, message, ready_at):
                    raise RuntimeError('Webhook did not return success')
            except (URLError, HTTPError, TimeoutError, RuntimeError, OSError) as ex:
                delay = min(3600, 30 * 2 ** min(attempts, 7))
                log.warning('Discord failed for %s %s; retry in %ss: %s', account, ident, delay, ex)
                db.execute("""UPDATE timers SET attempts=attempts+1,retry_after=?
                    WHERE account=? AND id=? AND ready_at=? AND status='pending'""",
                    (now + delay, account, ident, ready_at))
            else:
                db.execute("""UPDATE timers SET status='sent',retry_after=0
                    WHERE account=? AND id=? AND ready_at=? AND status='pending'""",
                    (account, ident, ready_at))
                log.info('Alert sent: %s / %s', account, label)


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/health':
            self.reply(200, {'ok': True})
        else:
            self.reply(404, {'error': 'not found'})

    def do_POST(self):
        if self.path != '/sync':
            return self.reply(404, {'error': 'not found'})
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + API_KEY):
            return self.reply(401, {'error': 'unauthorized'})
        try:
            size = int(self.headers.get('Content-Length', '-1'))
            if not 0 < size <= 16384:
                return self.reply(413, {'error': 'payload too large or empty'})
            payload = json.loads(self.rfile.read(size))
            count = apply_snapshot(payload)
        except (ValueError, TypeError, json.JSONDecodeError) as ex:
            return self.reply(400, {'error': str(ex)})
        self.reply(200, {'ok': True, 'timers': count})

    def log_message(self, fmt, *args):
        log.info('%s - %s', self.address_string(), fmt % args)


def scheduler():
    while True:
        try:
            process_due()
        except Exception:
            log.exception('Unexpected scheduler error')
        time.sleep(POLL_SECONDS)


def main():
    if len(API_KEY) < 24:
        raise SystemExit('API_KEY must be at least 24 characters')
    if not DISCORD_WEBHOOK.startswith(('https://discord.com/api/webhooks/', 'https://discordapp.com/api/webhooks/')):
        raise SystemExit('Set DISCORD_WEBHOOK to your Discord webhook URL')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    init_db()
    threading.Thread(target=scheduler, daemon=True).start()
    server = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    log.info('Time Tracking receiver listening on %s', PORT)
    server.serve_forever()


if __name__ == '__main__':
    main()

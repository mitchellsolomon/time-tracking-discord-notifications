import os
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app


class AppTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_path = app.DB_PATH
        app.DB_PATH = os.path.join(self.tmp.name, 'test.sqlite')
        app.init_db()

    def tearDown(self):
        app.DB_PATH = self.old_path
        self.tmp.cleanup()

    def test_dedup_and_reschedule(self):
        with patch.object(app.time, 'time', return_value=1000):
            payload = {'account': 'Player', 'timers': [{'id':'herbs','label':'Herb patches','ready_at':1020}]}
            app.apply_snapshot(payload)
            app.apply_snapshot(payload)
        messages = []
        with patch.object(app.time, 'time', return_value=1030):
            app.process_due(lambda account, label, message, when: messages.append((account,label,message,when)) or True)
            app.process_due(lambda account, label, message, when: messages.append((account,label,message,when)) or True)
        self.assertEqual(len(messages), 1)
        self.assertEqual(messages[0][2], '')
        with patch.object(app.time, 'time', return_value=1040):
            payload['timers'][0]['ready_at'] = 1060
            app.apply_snapshot(payload)
        with patch.object(app.time, 'time', return_value=1070):
            app.process_due(lambda account, label, message, when: messages.append((account,label,message,when)) or True)
        self.assertEqual(len(messages), 2)

    def test_empty_snapshot_cancels(self):
        with patch.object(app.time, 'time', return_value=1000):
            app.apply_snapshot({'account':'Player','timers':[{'id':'herbs','label':'Herbs','ready_at':1020}]})
            app.apply_snapshot({'account':'Player','timers':[]})
        messages = []
        with patch.object(app.time, 'time', return_value=1030):
            app.process_due(lambda *x: messages.append(x) or True)
        self.assertEqual(messages, [])

    def test_message_delivered(self):
        with patch.object(app.time, 'time', return_value=1000):
            app.apply_snapshot({'account': 'Player', 'timers': [{'id': 'hespori', 'label': 'Hespori',
                'message': 'Hespori is ready to fight.', 'ready_at': 1020}]})
        messages = []
        with patch.object(app.time, 'time', return_value=1030):
            app.process_due(lambda *x: messages.append(x) or True)
        self.assertEqual(messages, [('Player', 'Hespori', 'Hespori is ready to fight.', 1020)])

    def test_long_message_rejected(self):
        with self.assertRaises(ValueError):
            app.apply_snapshot({'account': 'Player', 'timers': [{'id': 'herb', 'label': 'Herbs',
                'message': 'x' * 201, 'ready_at': int(app.time.time()) + 60}]})

    def test_bad_timer_rejected(self):
        with self.assertRaises(ValueError):
            app.apply_snapshot({'account':'Player','timers':[{'id':'../','label':'oops','ready_at':0}]})


if __name__ == '__main__':
    unittest.main()

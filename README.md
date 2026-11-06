Time Tracking Discord Notifications
===================================

Get a Discord message when your Time Tracking items are ready: Hespori ready to fight, herbs ready to pick, bird houses ready to empty, and more.

This plugin works alongside RuneLite's built-in [Time Tracking](https://github.com/runelite/runelite/wiki/Time-Tracking) plugin. Time Tracking already knows when your patches and bird houses will be done. This plugin takes those same times and turns them into Discord notifications. Alerts still arrive after you log out or close RuneLite, so you know when to come back for your next run.

How to use
----------

1. **Turn on Time Tracking.** It's built into RuneLite and records your patches as you play. Leave it enabled.
2. **Set up a Discord webhook.** In your Discord server, open a channel's settings, then **Integrations → Webhooks → New Webhook**, and copy the webhook URL. Keep this URL private.
3. **Start the notification server** with that webhook URL (see [Notification server](#notification-server) below). The server sends your alerts on time, even with RuneLite closed.
4. **Configure the plugin.** Open the plugin's settings, enter your **Server URL** and **API key**, and tick **Allow data sync to server**.
5. **Pick your notifications.** Hespori, Herb patches and Bird houses are on by default. Turn on any other categories you want.
6. **Do your run as normal.** Plant your herbs, seed your bird houses, plant Hespori. RuneLite has to see a patch for Time Tracking to know about it. Within about a minute the ready time is sent to the server, and you'll get a Discord message when it's done.

Your notification will look something like this:

> **Hespori ready!**
> Hespori is fully grown and ready to fight at the Farming Guild.
> **YourName**

Time Tracking Discord Notifications Configuration
=================================================

Connection
----------

### 1. Allow Data Sync to Server
(Default Off) Nothing leaves your client until you turn this on. RuneLite will ask you to confirm, because this sends data to a server it doesn't control. When it's on, the plugin sends your character name, the category names and their ready times to the server you set below. Nothing else is sent.

### 2. Server URL
(Default empty) The address of your notification server, for example `http://192.168.1.50:8787/sync`.

### 3. API Key
(Default empty) The password your server expects. It must match the `API_KEY` set on the server.

Notifications
-------------

### 1. Patch Groups Ready When
(Default All patches ready) For patch categories such as herbs, choose whether to be notified when **every** patch in the category is ready, or as soon as the **first** one is. This works like Time Tracking's "Prefer soonest completion" setting.

### 2. Bird Houses
(Default On) Notifies you when all of your seeded bird houses on Fossil Island are ready to be dismantled.

### 3. Hespori
(Default On) Notifies you when Hespori is fully grown and ready to fight in the Farming Guild.

### 4. Herb Patches
(Default On) Notifies you when your herb patches are ready to harvest.

### 5. Farming Contract
(Default Off) Notifies you when your current farming contract crop in the Farming Guild is ready to harvest.

### 6. Allotment Patches
(Default Off) Notifies you when your allotment patches are ready to harvest.

### 7. Flower Patches
(Default Off) Notifies you when your flower patches are ready to harvest.

### 8. Tree Patches
(Default Off) Notifies you when your tree patches are fully grown.

### 9. Fruit Tree Patches
(Default Off) Notifies you when your fruit tree patches are fully grown.

### 10. Hops Patches
(Default Off) Notifies you when your hops patches are ready to harvest.

### 11. Bush Patches
(Default Off) Notifies you when your bush patches are ready to harvest.

### 12. Grape Patches
(Default Off) Notifies you when your grape vines are ready to harvest.

### 13. Special Patches
(Default Off) Notifies you when everything on Time Tracking's Special tab is ready. That covers mushroom, cactus, seaweed, belladonna, coral, anima, compost bins and Hespori. If you only care about Hespori, leave this off and use the Hespori option.

### 14. Timers
(Default Off) Notifies you when a countdown from Time Tracking's **Timers & Stopwatches** tab finishes.

Good to know
------------

- **Times are estimates**, the same ones you see in the Time Tracking panel. Disease, dead crops or patches you haven't visited since planting won't be known until RuneLite sees them again.
- **All your accounts are covered.** Every account you've logged into in RuneLite is tracked. Accounts on other worlds or modes, such as Leagues, show their mode next to the name.
- **Changed your mind?** Turning a category off cancels its pending alert within a minute. Replanting a patch updates the alert to the new time.
- **Timers out of sync?** Use Time Tracking's `::resetfarmtick` chat command, then stand by a tree patch until you see it grow.

Is this allowed?
----------------

This plugin is built to follow [Jagex's third-party client guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1) and RuneLite's [plugin rules](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features). It's a reminder tool: it only reads ready times that Time Tracking already shows you. It doesn't play the game for you, send any input, add menu options, read or change game traffic, or show anything hidden.

Sending data outside the client is opt-in and off by default. When you turn it on, RuneLite shows its standard warning that your IP address will be sent to a server RuneLite doesn't control. Only your character name, category names and ready times are sent, and only to the server you choose.

This plugin hasn't been reviewed by the RuneLite Plugin Hub yet. Only Jagex and RuneLite can give a final ruling on what's allowed.

Notification server
-------------------

Discord can only send your alert on time if something is awake when the timer runs out, even after RuneLite is closed. That's the job of the small server in [`server/`](server). Run it on any computer that stays on, such as a desktop, a Raspberry Pi or a cheap cloud server. It needs [Docker](https://docs.docker.com/get-docker/).

```bash
cd server
cp .env.example .env
```

Edit `.env`:

- `DISCORD_WEBHOOK`: your webhook URL from step 2
- `API_KEY`: a long random password (you can make one with `openssl rand -hex 32`). Enter the same value in the plugin.
- `BIND_IP`: the IP address of the computer running the server

Then start it:

```bash
docker compose up -d --build
```

Check it's running by opening `http://<BIND_IP>:8787/health`, which should show `{"ok":true}`. Your plugin's **Server URL** is then `http://<BIND_IP>:8787/sync`.

Keep the server on your own network, or reach it through something like [Tailscale](https://tailscale.com/). Don't expose it to the open internet.

Building from source
--------------------

You need JDK 11 or newer.

```bash
./gradlew test   # run the tests
./gradlew run    # launch a development RuneLite client with the plugin loaded
```

See RuneLite's [Developer Guide](https://github.com/runelite/runelite/wiki/Developer-Guide) for more on running plugins locally, and [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) if you log in with a Jagex account.

This project is licensed under the [BSD 2-Clause License](LICENSE). The farming data in `src/main/java/com/rmsolo/timetrackingdiscord/data/` is copied from RuneLite's Time Tracking plugin (BSD-2-Clause, original copyright headers kept). It needs updating when Jagex adds new patches or crops.

Server tests: `cd server && python3 -m unittest discover -s tests -v`

Found a bug or have an idea? [Open an issue](https://github.com/mitchellsolomon/time-tracking-discord-notifications/issues).

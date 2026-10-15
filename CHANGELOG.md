# What changed

Every push builds a new version on GitHub (**Releases → build-N**). Each entry says what's
different, in plain words, with an example. Newest first.

**What to update:**
- **App:** install `ioniq6-autolock.apk` from that build over the old one. Settings are kept.
- **Server:** in the Unraid terminal, `cd /mnt/user/appdata/ioniq6 && bash install.sh`.
- **ESP32:** only when an entry says so. Flash `esp32-car-tag.bin` at address `0x0`.

---

## Build 31 — Oct 15, 2026 · app

**At home, only the 20-second rule locks the car.**

- Before, in the first seconds after parking, while the app was still checking whether you were
  home (joining Wi-Fi, getting a GPS fix), a drop in the ESP32's signal could fire the normal
  walk-away lock first.
- Now the signal walk-away **waits until the home check is done**. That usually takes a second or
  two, and never more than 30 seconds.
- Example: you pull into the driveway and walk straight inside. The car locks **20 s after you
  left it, because of the home rule**, never because the signal dropped.
- Away from home nothing changes. As soon as GPS says you're not home (usually instantly), the
  signal walk-away works as before.

**Update:** app only.

---

## Build 30 — Oct 15, 2026 · app + server

**Every lock tells you why it happened, and every lock shows up in ntfy.**

- **The reason is in every lock message:** the app's pop-up, ntfy, and the home screen.
  Examples:
  - At home: **"Car locked. Locked at 5:03 PM because you'd been away from the car for 20 s
    (home rule)."** Home screen: "Away · Locked at 5:03 PM by home rule".
  - Away from home: **"…because you walked away: signal -96 dBm (threshold -87)."**
  - Phone died after parking: **"…because your phone went quiet after you parked."**
  - Backup check: **"…because the 6-hour backup check found it off and unlocked."**
- **Every lock now goes to ntfy**, not just problems. Since build 21, normal locks only showed in
  the app, so ntfy looked like it had stopped working.
- **If Hyundai made it wait, the message says so:** "It waited a moment because Hyundai was
  busy."

**Update:** app and server.

---

## Build 29 — Oct 15, 2026 · app + server

**Clearer messages when a lock has to wait.**

- **ntfy now tells you how a delayed lock ended.** Example from this afternoon: you walked away
  at 1:30 while Hyundai was still blocking the server. ntfy said the lock was delayed, then
  nothing more, even though the car locked at 1:35. Now you get a second message, **"Car locked"**,
  when it goes through. Normal locks still show only in the app, so no doubled-up messages.
- **The delay message is less alarming.** It says **"Lock delayed, still trying"** and that
  "Car locked" will follow, instead of "Auto-lock delayed".
- **The "Couldn't ask the car… until 1:31 PM" line under the status now disappears** once a lock
  goes through, or after 15 minutes. Before, it stayed on screen long after it stopped being true.

**Update:** app and server.

---

## Build 28 — Oct 15, 2026 · app + server

**The app tells you what's really going on with the car, and it asks Hyundai less often.**

**New statuses on the home screen**

| You are… | The app shows |
|---|---|
| In the car, connected to its Bluetooth, moving | **Driving** |
| In the car, connected to its Bluetooth, not moving | **In the car** |
| Just parked, near the car (ESP32 in range), no lock yet | **Not locked yet** · "Parked at 6:00 · locks when you walk away" |
| Near the car, and a lock was confirmed after you parked | **Locked** · "Parked · Locked at 6:02 by walk-away" |
| Away from the car (no car Bluetooth, no ESP32) | **Locked** · "Away · Locked at 6:02 by walk-away" |

- "Driving" now uses GPS. Example: you're sitting in the parked car with the radio on. Before, the
  app said "Driving". Now it says **In the car**. GPS runs only while the phone is connected to
  the car, so it costs almost no battery.
- "Not locked yet" stays until a lock is actually confirmed, however long that takes. Example:
  you unload groceries for 10 minutes. The screen keeps saying **Not locked yet** the whole time,
  then switches to **Locked** when the walk-away lock goes through.

**If the phone dies, the server still locks the car**
- When you park, the app tells the server "parked". While you're near the car it sends "still
  here" every couple of minutes.
- If those messages stop for 10 minutes and nothing has locked the car, the server locks it on
  its own and tells you on ntfy.
- Example: your phone's battery dies as you walk away from the car. Ten minutes later the server
  locks the car anyway.
- Driving off switches this off.

**Fewer requests to Hyundai**
- The app reads Hyundai's saved data (battery, range, climate) **at most twice an hour** by
  itself. That doesn't wake the car. The "how often" setting is gone.
- **A minute after every lock** (walk-away or the Lock button), it reads the saved data once more,
  so battery and range catch up. That doesn't wake the car either.
- **Refresh** still asks the car itself whenever you tap it.
- The server's backup check now looks **every 6 hours** instead of every 30 minutes. It skips
  the check if a lock was confirmed since the car was last used, or if your phone said it's at
  the car in the last 15 minutes. If Hyundai says the car is off and unlocked, it locks after a
  60-second cancel window.

**Update:** app and server. To turn the backup check back on, set `GUARD_INTERVAL_MINUTES=30`
in `.env` (any number above 0 turns it on), then run `docker compose up -d`.

---

## Build 27 — Oct 15 · server
**Waits longer after each refused sign-in.** If Hyundai's website shield (Cloudflare) refuses a
sign-in, the server now waits 10, 20, 40, 80, then 120 minutes before trying again, instead of
every 10 minutes. Knocking during a block keeps the block going. The log also records how long
Hyundai's sign-ins last.

## Build 26 — Oct 15 · app + server
**Far fewer sign-ins and car wake-ups.** The request log showed that the 30-minute backup check
was signing in to Hyundai about 48 times a day, because Hyundai's sign-ins only last ~30 minutes.
That's what got your home IP blocked.
- Each lock or command now checks your PIN once, not on every confirmation check. A lock went from
  up to ~50 requests to ~10.
- Automatic "ask the car" refreshes: at most 4 a day, 2 hours apart, never overnight.
- Two Refresh taps within a minute share one answer.

## Build 25 — Oct 15 · server
`install.sh doctor` no longer stops right after the "signed in to BlueLink" line.

## Build 24 — Oct 15 · server
**A record of every request to Hyundai** that survives server updates. `install.sh doctor` shows
how many of each kind went out in the last 24 h, and when Hyundai last refused one.
Example: `v2/login 4 (3 refused: rate limit)`.

## Build 23 — Oct 15 · app
- **Knows the car isn't being driven from the moment the app starts.** Before, a freshly installed
  app trusted Hyundai's old "Car on" from the morning.
- **Refreshes by itself when the data is clearly out of date.**

## Build 22 — Oct 15 · server
`doctor` finds the watchdog on Unraid (it said "not running" when it was).

## Build 21 — Oct 15 · app + server
**You feel and see every walk-away lock.**
- Walk away: two quick vibrations and **"Locking your car…"**. Then one firm buzz and **"Car
  locked"**, or a long buzz and **"Car NOT locked: trunk open"**.
- If Hyundai is rate-limiting, the lock is **delayed and retried, never dropped**, and you're told.
  Before, a lock could be given up silently. That's what happened at 8:32 that morning.
- The home screen stopped showing "Car on" after you'd parked.
- Home mode no longer counts starting the car as walking away.

## Build 20 — Oct 15 · app
**Home detection is automatic.** No switch: once your home Wi-Fi name (or location) is saved, the
app knows when you're home.

## Build 19 — Oct 15 · app + server
- **ARMED / NOT ARMED** badge on the walk-away card, and in the notification title.
- When the server skips a lock because the car is already locked, ntfy now says so quietly
  ("Car already locked") instead of staying silent.

## Build 18 — Oct 15 · app
- **Lock button like Cadillac's:** a spinning ring inside the button while it works, then a
  white flash with "Locked" when the car confirms (red shake if it fails), with vibration.
- **Cards** for Climate, Vehicle status (tires, 12 V, fluids), Charging (battery bar with a knob
  at your charge limit) and Find car.
- **Charge limits** for home (AC) and fast (DC) chargers, from the Charging card.

## Build 17 — Oct 15 · app + server
**Times shown in your time zone.** Hyundai sends UTC, so "Updated 3:02 AM" was really 11:02 PM.

## Build 16 — Oct 15 · app
Home mode locks **once per visit to the car**. Example: you go out to grab something from the
car and come back in, and it locks once more. Walking around the house never counts.

## Build 15 — Oct 15 · app
**Home mode:** at home (Wi-Fi or location), the car locks once after you park instead of every
time the ESP32's signal dips from inside the house.

## Build 14 — Oct 15 · server
**Runs on Unraid.** The installer keeps everything in `/mnt/user/appdata/ioniq6`, needs only port
443 forwarded on the eero, and sets up the watchdog with Unraid's cron.

## Builds 11–13 — Oct 15 · server
**Clear error messages instead of "unknown error"**, and the first back-off when Hyundai
rate-limits. Example: "Hyundai is rate-limiting this server, so it's pausing requests until about
10:45 PM."

## Build 10 — Oct 14 · app
**Redesign:** car photo at the top, Lock and Unlock buttons, Climate, Charging and Find car, the
live walk-away signal bar, and a separate Settings screen.

## Build 9 — Oct 14 · app
Arms after **30 seconds right next to the car**, even without a drive. Example: you walk to the
car, grab something, and walk away. It locks.

## Build 8 — Oct 14 · app + server
- **Power-bank aware:** the ESP32 is off while you drive and starts when you park. The app
  searches for it as soon as you park.
- Telling "ESP32 lost power" (you started the car) apart from "you walked away".
- **Pause 2 h** button, and a server watchdog that restarts it if it stops answering.

## Builds 5–7 — Oct 14 · ESP32 + server
- ESP32 firmware 1.1.0: the blue LED blinks while waiting and stays on when connected.
- Installer: adds swap on small servers, follows IP changes, updates itself, and adds `doctor`.

## Builds 1–4 — Oct 14
First versions: the ESP32 car tag, the Android walk-away app, the API server with a one-command
installer, and BlueLink email verification that finishes if interrupted.

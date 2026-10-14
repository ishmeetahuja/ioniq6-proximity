# Walk-away auto-lock — phone setup

The IONIQ 6 has no walk-away lock of its own, so the server does it: something tells it
"I've left the car", it waits a short grace period (default 20 s) and locks it
(details in `README.md` → *How walk-away auto-lock decides*). This file is about the
"something" — the trigger on your Android phone.

The key idea: **the trigger lives on the phone, because the phone always has internet.** Anything
in the car has to solve both power (USB ports are typically off once the car is off) and
connectivity, which is what made the ESP32 + hotspot idea painful.

## Stage 1 — car Bluetooth (no hardware, works today)

Your phone already pairs with the car. When you switch the car off and walk away, that Bluetooth
link drops — Android reports it even when the automation app is in the background. So:

- **Car Bluetooth disconnected** → `POST /v1/autolock/away`
- **Car Bluetooth connected** → `POST /v1/autolock/near` (cancels anything pending)

The grace period and the server's checks cover the awkward cases: a brief Bluetooth blip while
driving (it reconnects within seconds → `near` cancels), getting back in quickly (`near`
cancels), unloading with the trunk open (the lock is refused, so it waits until the trunk is
closed), or someone staying in the car (tap **Cancel auto-lock** on the notification).

Locking while you're still standing beside the car is harmless — the smart key in your pocket
unlocks it at the handle as usual.

Use your **PHONE_TOKEN** here, never the admin token — it can lock but never unlock.

### With MacroDroid

Find your car's Bluetooth name first (Settings → Connected devices; often "IONIQ 6" or similar).

**Macro "Left the car"**
- Trigger: *Connectivity → Bluetooth Event → Device Disconnected* → pick the car
- Action: *Connectivity → HTTP Request*
  - Method `POST`, URL `https://YOUR-DOMAIN/v1/autolock/away`
  - Header `Authorization` = `Bearer YOUR_PHONE_TOKEN`
  - Body (content type `application/json`): `{"source":"car-bluetooth"}`

**Macro "Back at the car"** — same, but *Device Connected* and URL `…/v1/autolock/near`.

### With Tasker

One profile does both:
- Profile: *State → Net → BT Connected* → Name: your car
- **Enter** task: *Net → HTTP Request*, POST, `https://YOUR-DOMAIN/v1/autolock/near`,
  Headers `Authorization:Bearer YOUR_PHONE_TOKEN`, Body `{"source":"car-bluetooth"}`
- **Exit** task: the same with `/v1/autolock/away`

### Make Android leave it alone

Background automations die quietly if Android's battery saver kills them:
- Settings → Apps → MacroDroid/Tasker → Battery → **Unrestricted**
- Samsung: also add it under Battery → Background usage limits → **Never sleeping apps**

### Test it

1. Sit in the car, switch it off, and watch `GET /v1/autolock` (or the ntfy app): you should
   see "Auto-lock armed" within a few seconds of the Bluetooth disconnect.
2. Walk away without locking. About 30–40 seconds later: "Car locked".
3. Repeat, but tap **Cancel auto-lock** on the notification — nothing should happen.

Tune the wait without redeploying: `PUT /v1/autolock/settings {"graceSeconds":10}` with the
admin token. Shorter locks sooner; longer gives you more time to tap Cancel. You can also put
`"graceSeconds"` in the phone's `away` request to vary it per trigger.

## Stage 2 — HOLYIOT beacon (true distance, optional)

Car Bluetooth tells the server "the car was switched off", not "you walked away". Usually the
grace period bridges that gap. If you want it to react to actual distance — lock 30 seconds
after you're out of range, but never while you're standing next to the car — the beacon is
the right tool:

- It's coin-cell powered, so it keeps advertising after the car is off. No wiring, no hotspot.
- The phone listens for it. Car Bluetooth disconnect **arms** the check; "beacon not seen
  for ~15 s" then sends `away` with `source: "beacon"` and `graceSeconds: 0` — the beacon has
  already confirmed you're gone, so there's nothing to wait for.

Beacon settings (HolyIOT Beacon app): iBeacon mode, advertising interval 300–500 ms, TX power
around 0 dBm. Put it somewhere central (glovebox / console), not against metal. Coin cells
lose capacity in hard cold, so after a northern-Ontario winter, check it more often.

The catch: there's no maintained no-code app that reliably watches iBeacons in the background
on current Android (Beacon Locator, the usual suggestion, hasn't been updated since 2019). So
this stage needs a small dedicated Android app — a foreground service that arms on car
Bluetooth disconnect, scans for your beacon's UUID, and calls the same API.

## Ideas compared

| Idea | Verdict |
|---|---|
| ESP32 in car + phone hotspot | Works, but the most moving parts: power for the ESP32 once the car is off, a second phone with a data plan living in the car through Sudbury winters and summers, and the ESP32 can't tell "car off" from "you left". |
| Phone app watching an ESP32 | Right instinct — the phone has the internet. But the ESP32 only duplicates the car's own Bluetooth, and still needs power when the car is off. Stage 1 gets the same signal for free. |
| HOLYIOT beacon + phone | Best "real distance" signal, no wiring. Needs a small custom app. → Stage 2. |
| Car Bluetooth disconnect | Zero hardware, reliable background event on Android. → Stage 1. |
| Server safety net | Catches what the phone misses (dead phone, Bluetooth off). Set `GUARD_INTERVAL_MINUTES=30`. |
| Phone geofence | Possible backup when Bluetooth is off, but slower and harder on the battery. The safety net covers the same gap. |
| Aftermarket plug-in auto-lock modules | Some are advertised for E-GMP cars (IONIQ 5/6, EV6). No phone involved, but check exactly what the module does before buying. |

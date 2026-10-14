# IONIQ 6 walk-away auto-lock — ESP32 tag + Android app

An ESP32 in the car, a small Android app on your phone, and the `ioniq6` API server:

```
car USB ──► power bank ──► ESP32 (Bluetooth tag in the glovebox)
                                ▲
                                │ Bluetooth LE: the phone watches the signal strength
                                │
                           Android app ──HTTPS──► ioniq6 API ──► BlueLink ──► car locks
```

1. You switch the car off and get out. The phone loses the **car's** Bluetooth → the app arms.
2. You walk away. The **ESP32's** signal fades below your threshold (or the link drops) →
   the app tells the server `away` with no grace period → the server locks the car.
3. Walk back and stay near the car for a minute → it re-arms, so leaving again locks again.

The ESP32 needs no Wi-Fi or internet. The phone does the talking.

Every push to `main` builds both pieces on GitHub and publishes them under **Releases**:
`ioniq6-autolock.apk` (phone) and `esp32-car-tag.bin` (ESP32).

---

## 0 · The server on AWS (once)

The server (`server/`) is the piece that talks to Hyundai. GitHub builds it as a container
image; an installer sets everything up on a small AWS server, including HTTPS.

1. **Create the server.** AWS console → **Lightsail** → Create instance:
   region **Canada (Montreal)**, platform **Linux**, blueprint **OS Only → Ubuntu 24.04 LTS**,
   the smallest plan. Create it.
2. **Give it a fixed address.** Instance → **Networking** → **Attach static IP** (create one).
   Hyundai ties each login to an IP, so the address must not change.
3. **Open HTTPS.** Same Networking tab → IPv4 firewall → **Add rule → HTTPS (443)**.
   (SSH 22 and HTTP 80 are already open; 80 is needed for the certificate.)
4. **Install.** Click **Connect using SSH** (opens a terminal in the browser) and paste:

   ```bash
   curl -fsSL https://get.docker.com | sudo sh
   sudo docker run --rm ghcr.io/ishmeetahuja/ioniq6-api cat /app/install.sh > install.sh && sudo bash install.sh
   ```

   It asks for a hostname (press Enter — it uses a free `<your-ip>.sslip.io` name with a real
   HTTPS certificate), your BlueLink email, password and PIN, then the code Hyundai emails you.
   At the end it prints your **server address** and **phone token** for the app, and an
   **ntfy topic** for notifications.

- Update later: `cd /opt/ioniq6 && sudo bash install.sh`. It also fixes the address if the
  server's IP changed, and adds swap on small (512 MB) servers so they don't freeze.
- Redo Hyundai's email verification: `cd /opt/ioniq6 && sudo bash install.sh login`
- Logs: `cd /opt/ioniq6 && sudo docker compose logs -f api`

**Tokens, briefly:** the installer makes two random passwords. The **phone token** goes in
the app: it can lock the car and run auto-lock, but can never unlock or start it — so a lost
phone or a leaked automation can't open the car. The **admin token** (in `/opt/ioniq6/.env`)
can do everything; keep it to yourself. Full API reference: `server/README.md`.

## 1 · Flash the ESP32

No software to install — use Chrome or Edge on a computer:

1. Download `esp32-car-tag.bin` from the latest release.
2. Plug the ESP32 into the computer with a **data** USB cable (some cables are charge-only).
3. Open <https://espressif.github.io/esptool-js/>, click **Connect**, pick the board's port.
   - Nothing shows up? Windows may need the CP210x USB driver from Silicon Labs.
   - Won't connect? Hold the board's **BOOT** button while clicking Connect.
4. Set **Flash Address** to `0x0`, choose the `.bin` file, click **Program**.
5. Close the flasher tab, then **unplug the ESP32 and plug it back in** (the browser can hold
   the chip in flashing mode until it's power-cycled). The **blue** LED now blinks every
   2 seconds: it's waiting for a phone. (The red LED is just power.) Once the app connects,
   the blue LED stays on.
6. Not sure? Install **nRF Connect** on the phone and scan: you should see `IONIQ6-PROX`.

## 2 · Put it in the car

Car USB port → power bank → ESP32. Glovebox or centre console, not on the dash in the sun.

Check the power bank first: plug the ESP32 into it, leave it 5 minutes, and make sure the LED is
still blinking. Some power banks switch off when the load is this small — use one with a
low-current / "trickle" mode (often a double-press of its button), or a different bank.

## 3 · Install the app

1. On the phone, open the latest release and download `ioniq6-autolock.apk`.
2. Open it. Android asks to allow installs from your browser — allow it once.
3. Updates later: download the newer APK and install it over the old one; settings are kept.

## 4 · Set up the app

1. **Server**: your API address (`https://…`) and the **PHONE_TOKEN** (never the admin
   token). Tap **Test**.
2. **Choose car**: pick your car from the paired Bluetooth devices.
3. **Find ESP32**: with the ESP32 powered nearby, pick `IONIQ6-PROX`.
4. Turn on **Monitoring** and tap **Let it run in the background**.
   On Samsung, also: Settings → Battery → Background usage limits → **Never sleeping apps** → add it.
5. **Threshold**: put the ESP32 in its spot in the car. Walk away to where you'd want the car to
   lock (10–15 m is a good start) and watch the live signal on the app's status line. Tap
   **Use current signal**. Typical values are −80 to −90 dBm.

## 5 · Test it

1. Turn **Monitoring** on, tap **Test: arm as if I just parked**, and walk away. The log shows
   `Away: signal … dBm` and `away → HTTP 202`, and the server's notification says the car locked.
2. Then for real: drive, park, switch off, walk away.

On the server, auto-lock is on and set to fast mode (the defaults).

## How it decides

| Situation | What happens |
|---|---|
| Car Bluetooth connects | **Driving.** Any pending lock is cancelled. |
| Car Bluetooth disconnects | **Parked.** Starts watching the ESP32. |
| Signal below threshold for 3 s, or link down for 5 s | **Away.** Server locks immediately. |
| First 10 s after parking | Ignores ESP32 hiccups (the power bank switching over can reboot it). |
| ESP32 never connects within 25 s of parking | Falls back to car Bluetooth only; the server uses its normal grace period. |
| Back near the car (strong signal) for 60 s | Re-arms. The next walk-away asks the server to check first, so no redundant lock if it's already locked. |
| No network when you walk away | Retries with back-off for several minutes. The server's safety-net check covers the rest. |

It never unlocks anything.

## Building locally (optional)

- Firmware: [PlatformIO](https://platformio.org/) → `pio run -d firmware -t upload`
- App: open `android/` in Android Studio.

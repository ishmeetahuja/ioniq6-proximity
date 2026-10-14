# ioniq6 — BlueLink Canada CLI + API + walk-away auto-lock

Control a Hyundai IONIQ 6 through Hyundai BlueLink Canada (mybluelink.ca) from the terminal,
over HTTP, and automatically lock it when you walk away. No dependencies, Node 18+.

| File | What it is |
|---|---|
| `ioniq6.js` | Command-line tool |
| `server.js` | HTTP API + auto-lock engine (what you host on AWS) |
| `lib/bluelink-ca.js` | The BlueLink Canada client both use |
| `Dockerfile`, `docker-compose.yml`, `Caddyfile`, `.env.example` | Deployment |
| `PROXIMITY.md` | Phone setup for walk-away auto-lock |

---

## CLI

```bash
ioniq6 setup                 # email, password, PIN -> ~/.ioniq6/config.json (mode 600)
ioniq6 login                 # first time: Hyundai emails/texts a code
ioniq6 status [--refresh]    # --refresh wakes the car for live data
ioniq6 location
ioniq6 lock | unlock
ioniq6 start --temp 22 --duration 15 --defrost --steering --seat-driver high-heat
ioniq6 stop
ioniq6 charge start | stop
ioniq6 charge-limit [--ac 80 --dc 90]
ioniq6 trips
ioniq6 logout [--forget-device]
```

`--json` on anything for scripting, `--no-wait` to skip waiting for the car's confirmation,
`IONIQ6_DEBUG=1` for raw API responses (tokens masked). Non-interactive login:
`ioniq6 login --otp-method email` then `ioniq6 login --otp 123456`.

---

## HTTP API

Every request except `/healthz` and the notification cancel link needs
`Authorization: Bearer <token>`. There are two tokens:

- **ADMIN_TOKEN** — everything.
- **PHONE_TOKEN** — for phone automations: status, **lock**, and auto-lock. It can't unlock,
  start climate, or read the car's location, so a leaked automation token can't open the car.

Ten bad tokens from one IP locks that IP out for 15 minutes.

### Endpoints

| Method & path | Token | Body / query | Notes |
|---|---|---|---|
| `GET /healthz` | none | | Liveness |
| `GET /v1/status` | phone | `?refresh=true` | Battery, range, charging, locks, doors, climate… |
| `GET /v1/location` | admin | | `{lat, lon, time, maps}` |
| `GET /v1/charge-limit` | admin | | `{ac, dc}` |
| `GET /v1/trips` | admin | | Recent EV trips |
| `GET /v1/vehicles` | admin | | Vehicles on the account |
| `POST /v1/lock` | phone | `?wait=true` | |
| `POST /v1/unlock` | admin | `?wait=true` | |
| `POST /v1/climate/start` | admin | `{"temp":22,"duration":15,"defrost":true,"steering":true,"rearWindow":true,"seats":{"driver":"high-heat","passenger":"low-heat"}}` | Seat levels: `off low-heat med-heat high-heat low-cool med-cool high-cool` |
| `POST /v1/climate/stop` | admin | | |
| `POST /v1/charge/start` · `/stop` | admin | | |
| `PUT /v1/charge-limit` | admin | `{"ac":80,"dc":90}` | 50–100 in steps of 10 |
| `GET /v1/jobs/:id` | phone | | Result of a command |
| `POST /v1/auth/login` | admin | `{"otpMethod":"email"\|"sms"}` | One-time setup, see below |
| `POST /v1/auth/otp` | admin | `{"code":"123456"}` | |
| `GET /v1/auth` | admin | | Logged in? Which car? |
| `GET /v1/autolock` | phone | | State + last 20 events |
| `POST /v1/autolock/away` | phone | `{"source":"car-bluetooth","graceSeconds":20,"checkFirst":true}` | "I've left the car". `graceSeconds` (0–600) and `checkFirst` override the defaults for this one event |
| `POST /v1/autolock/near` | phone | `{"source":"car-bluetooth"}` | "I'm back" — cancels a pending lock |
| `POST /v1/autolock/cancel` | phone | | Cancel the pending lock |
| `POST /v1/autolock/pause` | phone | `{"minutes":120}` | `0` resumes |
| `POST /v1/autolock/enable` · `/disable` | admin | | Persistent on/off |
| `PUT /v1/autolock/settings` | admin | `{"graceSeconds":15,"checkFirst":false}` | Change defaults without redeploying (persisted) |

**Commands are asynchronous by default**: they return `202` with a job id right away (the car
takes 10–60 s to answer), and you poll `GET /v1/jobs/:id`. Add `?wait=true` to block until the
car confirms. Commands run one at a time, in order.

Examples:

```bash
API=https://car.example.com
curl -H "Authorization: Bearer $ADMIN" $API/v1/status
curl -X POST -H "Authorization: Bearer $ADMIN" "$API/v1/lock?wait=true"
curl -X POST -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"temp":22,"duration":15,"seats":{"driver":"high-heat"}}' $API/v1/climate/start
```

### How walk-away auto-lock decides

1. Your phone calls `/v1/autolock/away` (see `PROXIMITY.md` for how).
2. The server waits the grace period (default **20 s**) and sends a quiet notification with a
   **Cancel auto-lock** button. `/near` or the button cancels it.
3. **Fast mode (default):** it sends the lock command straight away — locking an
   already-locked car is harmless. You're typically locked ~30–40 s after leaving the car
   (20 s grace + the car's own 10–20 s to act on the command).
   Only if the car refuses does it wake the car to find out why:
   - **a door, trunk or frunk is open** → tells you, re-checks every 30 s, locks once it's
     closed, or sends a high-priority alert after 5 tries
   - **car is on** / **already locked** → leaves it
4. **Check-first mode** (`checkFirst: true`) inspects the car *before* locking. It's quieter —
   no redundant lock (and hazard-light flash) when you'd already locked it with the handle —
   but adds the 10–30 s it takes to wake the car.
5. **Safety net** (`GUARD_INTERVAL_MINUTES`): independently of the phone, the server checks the
   cheap cached status every N minutes and runs the same flow if the car is off and unlocked on two
   checks in a row — so a dead phone or missed trigger still gets caught.

It never unlocks anything on its own.

---

## Deploy on AWS

**Use a small always-on server (Lightsail or EC2), not Lambda.** Two reasons: Hyundai Canada
ties each login token to the IP that made it, so Lambda's rotating IPs would force a fresh login
on nearly every call; and auto-lock needs timers that keep running between requests.

### 1. Server

1. In **Lightsail**, create an instance in **Canada (Montreal), ca-central-1** — Ubuntu 24.04,
   the smallest plan is plenty. A Canadian IP is the closest match to what Hyundai's portal expects.
2. Create a **static IP** and attach it (Networking tab). The static IP matters: it keeps the
   Hyundai token valid between calls.
3. In the instance's **Networking → IPv4 firewall**, allow TCP **80** and **443**. Restrict SSH (22)
   to your own IP if you can.

### 2. Hostname

Point a domain you own at the static IP (an `A` record), **or** skip the domain and use
`<your-ip-with-dashes>.sslip.io` (e.g. `15-222-1-23.sslip.io`), which resolves to that IP.
Caddy gets a Let's Encrypt certificate for either.

### 3. Install and start

```bash
ssh ubuntu@<static-ip>
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit          # log out and back in
# copy this folder up (scp -r, git, etc.), then:
cd ioniq6
cp .env.example .env
openssl rand -hex 24      # run twice: one for ADMIN_TOKEN, one for PHONE_TOKEN
nano .env                 # fill in DOMAIN, PUBLIC_URL, BlueLink creds, tokens, NTFY_URL
docker compose up -d --build
docker compose logs -f api
```

### 4. One-time BlueLink verification for the server

The server is a new "device" to Hyundai, so it needs the email/SMS code once:

```bash
API=https://car.example.com; ADMIN=<your admin token>
curl -X POST -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"otpMethod":"email"}' $API/v1/auth/login        # -> {"status":"otp_sent", ...}
curl -X POST -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"code":"123456"}' $API/v1/auth/otp              # -> {"status":"logged_in", ...}
curl -H "Authorization: Bearer $ADMIN" $API/v1/status
```

The device id is kept in the `ioniq6_data` Docker volume, so restarts and redeploys don't
ask again. (Removing that volume does.)

### 5. Notifications

Install **ntfy** on your phone (Play Store or F-Droid), subscribe to the same topic name you
put in `NTFY_URL`, and use a long random topic — on the public ntfy.sh server, anyone who
guesses the name can read it. Telegram works too (`TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID`).

### Updating

```bash
cd ioniq6 && git pull   # or copy new files up
docker compose up -d --build
```

---

## Caveats

- This uses Hyundai's unofficial web API. It can break when Hyundai changes the portal, and
  automated use may be against BlueLink's terms of service.
- Hyundai may rate-limit or block cloud IP ranges. If AWS gets blocked, the same
  `docker compose` setup runs on any home Linux box (expose it with a Cloudflare Tunnel or
  Tailscale instead of opening ports).
- Each auto-lock check wakes the car once for a live status; that's a tiny 12V draw, but don't
  set the safety check below 30 minutes.
- Protocol ported from [hyundai_kia_connect_api](https://github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api)
  (`KiaUvoApiCA.py`, Oct 2026).

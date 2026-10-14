#!/bin/bash
# ioniq6 API — one-shot installer for a fresh Ubuntu server (e.g. AWS Lightsail).
#
#   curl -fsSL https://get.docker.com | sudo sh
#   sudo docker run --rm ghcr.io/ishmeetahuja/ioniq6-api cat /app/install.sh > install.sh && sudo bash install.sh
#
# First run: asks for your BlueLink login, generates tokens, sets up HTTPS (free certificate,
# no domain needed), starts everything and does Hyundai's one-time email verification.
# Run it again later to update to the newest version:  cd /opt/ioniq6 && sudo bash install.sh
# Redo Hyundai's email verification:                  cd /opt/ioniq6 && sudo bash install.sh login
set -euo pipefail
SELF=$(readlink -f "$0")

DIR=/opt/ioniq6
IMAGE=ghcr.io/ishmeetahuja/ioniq6-api:latest

if [ "$(id -u)" -ne 0 ]; then echo "Run with sudo: sudo bash install.sh"; exit 1; fi
command -v docker >/dev/null || { echo "Docker isn't installed. Run: curl -fsSL https://get.docker.com | sudo sh"; exit 1; }
mkdir -p "$DIR"
[ "$SELF" != "$DIR/install.sh" ] && cp "$SELF" "$DIR/install.sh"
cd "$DIR"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
api()  { # api METHOD PATH [JSON] — calls the API from inside its container with the admin token
  docker compose exec -T -e M="$1" -e P="$2" -e B="${3:-}" api node -e '
    fetch("http://127.0.0.1:8080" + process.env.P, {
      method: process.env.M,
      headers: { Authorization: "Bearer " + process.env.ADMIN_TOKEN, "Content-Type": "application/json" },
      body: process.env.B || undefined,
    }).then((r) => r.text()).then((t) => console.log(t)).catch((e) => console.log(JSON.stringify({ error: e.message })));'
}
wait_healthy() {
  for _ in $(seq 1 30); do
    docker compose exec -T api wget -qO- http://127.0.0.1:8080/healthz >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "The API didn't start. Logs:"; docker compose logs --tail 30 api; exit 1
}

do_login() {
  say "Signing in to BlueLink"
  local R CODE
  R=$(api POST /v1/auth/login '{"otpMethod":"email"}')
  if echo "$R" | grep -q '"otp_sent"'; then
    echo "Hyundai just emailed a verification code to your BlueLink address."
    for _ in 1 2 3; do
      read -rp "Code from the email: " CODE
      R=$(api POST /v1/auth/otp "{\"code\":\"${CODE//[^0-9]/}\"}")
      echo "$R" | grep -q '"logged_in"' && break
      echo "Not accepted: $R"
    done
  fi
  if echo "$R" | grep -q '"logged_in"'; then
    echo "Signed in."
  else
    echo "BlueLink sign-in didn't finish: $R"
    echo "Retry with:  cd $DIR && sudo bash install.sh login"
  fi
}

show_details() {
  local url token ntfy
  url=$(grep '^PUBLIC_URL=' .env | cut -d= -f2-)
  token=$(grep '^PHONE_TOKEN=' .env | cut -d= -f2-)
  ntfy=$(grep '^NTFY_URL=' .env | cut -d= -f2-)
  say "For the phone app:"
  echo "  Server:       ${url}"
  echo "  Phone token:  ${token}"
  [ -n "$ntfy" ] && echo "  ntfy topic:   ${ntfy##*/}"
}

# Small instances (512 MB) have no swap by default; a memory spike (image pulls, OS updates)
# can freeze the whole box. 1 GB of swap on disk prevents that.
ensure_swap() {
  local mem_mb
  mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
  [ "$mem_mb" -lt 1500 ] || return 0
  [ "$(swapon --show --noheadings | wc -l)" -eq 0 ] || return 0
  say "Adding 1 GB of swap (this server has ${mem_mb} MB of RAM)"
  fallocate -l 1G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=1024 status=none
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
}

# The free *.sslip.io name contains the server's IP. If the IP changed (restart without a
# static IP, or a static IP attached later), point the name at the new IP.
fix_ip() {
  local dom ip want
  dom=$(grep '^DOMAIN=' .env | cut -d= -f2-)
  case "$dom" in *.sslip.io) ;; *) return 0 ;; esac
  ip=$(curl -fsS https://checkip.amazonaws.com 2>/dev/null || true)
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 0
  want="${ip//./-}.sslip.io"
  [ "$dom" = "$want" ] && return 0
  say "This server's IP address changed"
  echo "  old: $dom"
  echo "  new: $want"
  sed -i "s|^DOMAIN=.*|DOMAIN=${want}|; s|^PUBLIC_URL=.*|PUBLIC_URL=https://${want}|" .env
  echo "Put the new server address (below) in the phone app."
  echo "To stop this happening again: Lightsail → your instance → Networking → attach a static IP."
  echo "(Attaching one changes the IP once more — just run this installer again afterwards.)"
}

write_files() {
  cat > Caddyfile <<'EOF2'
{$DOMAIN} {
	encode gzip
	reverse_proxy api:8080
}
EOF2
  cat > docker-compose.yml <<EOF2
x-logging: &logging
  driver: json-file
  options: { max-size: "10m", max-file: "3" }
services:
  api:
    image: ${IMAGE}
    env_file: .env
    environment:
      TRUST_PROXY: "true"
    volumes:
      - ioniq6_data:/data
    restart: unless-stopped
    logging: *logging
  caddy:
    image: caddy:2
    ports: ["80:80", "443:443"]
    environment:
      DOMAIN: \${DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on: [api]
    restart: unless-stopped
    logging: *logging
volumes:
  ioniq6_data:
  caddy_data:
  caddy_config:
EOF2
}

# ------------------------------------------------------------------ redo verification only
if [ "${1:-}" = "login" ]; then
  [ -f .env ] || { echo "Not installed yet — run without 'login' first."; exit 1; }
  wait_healthy
  do_login
  show_details
  exit 0
fi

# ------------------------------------------------------------------ update an existing install
if [ -f .env ]; then
  say "Existing install in $DIR — updating to the latest version"
  ensure_swap
  if [ -z "${IONIQ6_REEXEC:-}" ]; then
    docker compose pull
    # Continue with the installer from the new image, so updates also update this script.
    NEW=$(mktemp)
    docker run --rm "$IMAGE" cat /app/install.sh > "$NEW" 2>/dev/null || true
    if [ -s "$NEW" ] && ! cmp -s "$NEW" "$DIR/install.sh"; then
      cp "$NEW" "$DIR/install.sh"; rm -f "$NEW"
      echo "Installer updated — continuing with the new version."
      IONIQ6_REEXEC=1 exec bash "$DIR/install.sh" "$@"
    fi
    rm -f "$NEW"
  fi
  fix_ip
  write_files
  docker compose up -d
  wait_healthy
  if api GET /v1/auth | grep -q '"loggedIn": false'; then
    echo "Not signed in to BlueLink yet — finishing that now."
    do_login
  else
    echo "Updated and signed in to BlueLink."
  fi
  show_details
  exit 0
fi

# ------------------------------------------------------------------ first install
say "IONIQ 6 API setup"
ensure_swap
IP=$(curl -fsS https://checkip.amazonaws.com 2>/dev/null || true)
DEFAULT_HOST=""
[ -n "$IP" ] && DEFAULT_HOST="${IP//./-}.sslip.io"
echo "Use a domain you own, or press Enter for a free name based on this server's IP."
read -rp "Hostname [${DEFAULT_HOST}]: " HOST
HOST=${HOST:-$DEFAULT_HOST}
[ -n "$HOST" ] || { echo "Need a hostname."; exit 1; }

read -rp  "BlueLink email: " BL_USER
read -rsp "BlueLink password: " BL_PASS; echo
read -rsp "BlueLink 4-digit PIN: " BL_PIN; echo
[[ "$BL_PIN" =~ ^[0-9]{4}$ ]] || { echo "The PIN must be 4 digits."; exit 1; }
for v in "$BL_USER" "$BL_PASS"; do
  [[ "$v" == *"'"* ]] && { echo "Sorry, this installer can't handle a ' in the email or password. Edit $DIR/.env by hand instead."; exit 1; }
done

ADMIN=$(openssl rand -hex 24)
PHONE=$(openssl rand -hex 24)
TOPIC="ioniq6-$(openssl rand -hex 8)"

umask 077
cat > .env <<EOF
DOMAIN=${HOST}
PUBLIC_URL=https://${HOST}
BLUELINK_USERNAME='${BL_USER}'
BLUELINK_PASSWORD='${BL_PASS}'
BLUELINK_PIN=${BL_PIN}
ADMIN_TOKEN=${ADMIN}
PHONE_TOKEN=${PHONE}
AUTOLOCK_ENABLED=true
AUTOLOCK_GRACE_SECONDS=20
AUTOLOCK_CHECK_FIRST=false
GUARD_INTERVAL_MINUTES=30
NTFY_URL=https://ntfy.sh/${TOPIC}
EOF
umask 022

write_files

say "Starting…"
docker compose pull
docker compose up -d
wait_healthy

do_login

# ------------------------------------------------------------------ HTTPS check
say "Getting the HTTPS certificate (up to a minute)"
OK=""
for _ in $(seq 1 30); do
  if curl -fsS "https://${HOST}/healthz" >/dev/null 2>&1; then OK=1; break; fi
  sleep 2
done
if [ -z "$OK" ]; then
  echo "HTTPS isn't answering yet. Most likely port 443 is closed: in Lightsail → your instance →"
  echo "Networking → IPv4 firewall → Add rule → HTTPS. It will start working within a minute after that."
fi

say "Done. Put these in the phone app:"
echo "  Server:       https://${HOST}"
echo "  Phone token:  ${PHONE}"
echo
echo "Notifications: install the ntfy app and subscribe to topic:  ${TOPIC}"
echo
echo "Admin token (keep private — it can unlock the car): saved in ${DIR}/.env"
echo "Update later:  cd ${DIR} && sudo bash install.sh"

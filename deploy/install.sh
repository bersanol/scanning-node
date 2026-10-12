#!/bin/bash
# Node installer for clean Ubuntu and RHEL hosts (requirement 1 / plan.md §12 Part 3).
#
# Run as root from the installer bundle: this script plus node-<version>.jar and
# node-start-with-rollback.sh in the same directory (copied to the host like any package).
# The node JAR already contains the manager's certificate, so nothing is downloaded from the
# manager here; the host only needs its OS package repositories.
#
#   ./install.sh --manager https://manager.example:8443 --token <enrollment token>
#
# Supported (validated): Ubuntu 22.04 / 24.04, RHEL 9 (validated on Rocky Linux 9). RHEL 8 and
# other rebuilds (Alma) use the same dnf path but are not validated.
#
# Re-running is safe: identity, state and an already upgraded release are kept; only the config
# and service unit are rewritten and the service restarted.
set -euo pipefail

MANAGER_URL="" TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --manager) MANAGER_URL="$2"; shift 2 ;;
    --token)   TOKEN="$2"; shift 2 ;;
    *) echo "usage: $0 --manager <https://host:8443> --token <enrollment token>" >&2; exit 2 ;;
  esac
done
[ -n "$MANAGER_URL" ] && [ -n "$TOKEN" ] || { echo "--manager and --token are required" >&2; exit 2; }
[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }

BUNDLE="$(cd "$(dirname "$0")" && pwd)"
JAR="$(ls "$BUNDLE"/node-*.jar 2>/dev/null | sort -V | tail -1)"
[ -n "$JAR" ] || { echo "no node-<version>.jar next to $0" >&2; exit 1; }
APP_VERSION="$(basename "$JAR" .jar)"; APP_VERSION="${APP_VERSION#node-}"

APP_DIR=/opt/scanning-node
STATE_DIR=/var/lib/scanning-node
CONF_DIR=/etc/scanning-node
UNIT=/etc/systemd/system/scanning-node.service

# --- 1. OS detection and dependencies (JDK 21 + nmap) ---
# Read only what we need: sourcing os-release would clobber variables such as VERSION.
OS_ID="$(. /etc/os-release && echo "$ID $ID_LIKE")"
VERSION_ID="$(. /etc/os-release && echo "$VERSION_ID")"
PRETTY_NAME="$(. /etc/os-release && echo "$PRETTY_NAME")"
case "$OS_ID" in
  *ubuntu*|*debian*)
    [[ "$VERSION_ID" =~ ^(22\.04|24\.04)$ ]] || echo "warning: $PRETTY_NAME is not a validated version"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -q
    apt-get install -y -q openjdk-21-jre-headless nmap
    ;;
  *rhel*|*fedora*|*centos*)
    [[ "$VERSION_ID" =~ ^9 ]] || echo "warning: $PRETTY_NAME is not a validated version"
    dnf install -y -q java-21-openjdk-headless nmap
    ;;
  *) echo "unsupported OS: $PRETTY_NAME (need Ubuntu or RHEL)" >&2; exit 1 ;;
esac

# --- 2. Application files. Releases are versioned; `current` points at the one to run. ---
mkdir -p "$APP_DIR/releases/$APP_VERSION" "$APP_DIR/bin"
install -m 0644 "$JAR" "$APP_DIR/releases/$APP_VERSION/node.jar"
install -m 0755 "$BUNDLE/node-start-with-rollback.sh" "$APP_DIR/bin/node-start-with-rollback.sh"
# Keep `current` on reinstall: the node may have been upgraded past the bundled version.
[ -e "$APP_DIR/current" ] || ln -sfn "$APP_DIR/releases/$APP_VERSION/node.jar" "$APP_DIR/current"

# --- 3. Configuration (holds the enrollment token, so root-only) ---
mkdir -p "$CONF_DIR"
umask 077
cat > "$CONF_DIR/node.env" <<EOF
SCANNING_MANAGER_URL=$MANAGER_URL
SCANNING_ENROLLMENT_TOKEN=$TOKEN
SCANNING_STATE_DIR=$STATE_DIR
SCANNING_APP_DIR=$APP_DIR
EOF
umask 022

# --- 4. State: identity.json is never touched, so a reinstall keeps the node's identity. ---
mkdir -p "$STATE_DIR/outbox"
chmod 0700 "$STATE_DIR"
if [ -f "$STATE_DIR/identity.json" ]; then EVENT=REINSTALLED; else EVENT=INSTALLED; fi
# Audit event (requirement 8): dropped into the outbox; the agent delivers it after it connects.
EVENT_ID="$(cat /proc/sys/kernel/random/uuid)"
cat > "$STATE_DIR/outbox/event-$EVENT_ID.json" <<EOF
{"eventId":"$EVENT_ID","type":"$EVENT","detail":"installer, bundled app $APP_VERSION on $PRETTY_NAME","occurredAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF

# --- 5. systemd service: starts on boot, restarted after every exit (app upgrades exit 0). ---
# Runs as root because nmap upgrades install OS packages with apt-get/dnf.
cat > "$UNIT" <<EOF
[Unit]
Description=Remote Scanning Node agent
Wants=network-online.target
After=network-online.target

[Service]
EnvironmentFile=$CONF_DIR/node.env
ExecStart=$APP_DIR/bin/node-start-with-rollback.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable scanning-node.service
systemctl restart scanning-node.service

echo "$EVENT: scanning-node (bundled $APP_VERSION, running $(readlink "$APP_DIR/current")) -> $MANAGER_URL"
echo "logs: journalctl -u scanning-node -f"

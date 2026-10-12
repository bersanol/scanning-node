#!/bin/bash
# Node launcher with automatic upgrade rollback (requirement 6 / plan.md §6, step 6).
#
# Why a shell script: a broken new release cannot roll itself back because it never starts, so
# the rollback has to live outside the JVM. systemd (Restart=always) or the Docker entrypoint
# loop runs this script on every (re)start.
#
# How it works:
#   - $APP_DIR/current is a symlink to the node JAR that should run.
#   - During an app upgrade the agent writes upgrade.json with state "SWAPPED" (plus "from" =
#     previous version), repoints `current` to the new JAR, and exits so this script restarts it.
#   - Every start while the state is still SWAPPED counts as an attempt. The new release clears
#     SWAPPED once it starts and reports in. If it fails to start 3 times, this script repoints
#     `current` to the previous release and marks the upgrade ROLLED_BACK; the old version then
#     reports the rollback to the manager.
set -u
APP_DIR="${SCANNING_APP_DIR:-/opt/scanning-node}"
STATE_DIR="${SCANNING_STATE_DIR:-/var/lib/scanning-node}"
UPGRADE="$STATE_DIR/upgrade.json"        # written by the agent during an app upgrade
ATTEMPTS="$STATE_DIR/start-attempts"     # start counter for the freshly swapped release

if [ -f "$UPGRADE" ] && grep -q '"SWAPPED"' "$UPGRADE"; then
  n=$(( $(cat "$ATTEMPTS" 2>/dev/null || echo 0) + 1 ))
  echo "$n" > "$ATTEMPTS"
  if [ "$n" -gt 3 ]; then
    # Three failed starts: go back to the release we upgraded from.
    from=$(sed -E 's/.*"from":"([^"]+)".*/\1/' "$UPGRADE")
    echo "node-start-with-rollback.sh: new release failed to start $((n-1)) times; rolling back to $from"
    ln -sfn "$APP_DIR/releases/$from/node.jar" "$APP_DIR/current"
    sed -i 's/"SWAPPED"/"ROLLED_BACK"/' "$UPGRADE"
    rm -f "$ATTEMPTS"
  fi
fi

# exec: the JVM replaces this shell, so systemd/Docker see the node process directly.
exec java -jar "$APP_DIR/current"

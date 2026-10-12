#!/bin/bash
# One-command demo: the manager plus two clean Linux hosts (Ubuntu 22.04, Rocky 9) with the node
# installed on each by the real installer.
#
#   ./demo.sh          manager in Docker; needs only Docker (installed if missing, after asking)
#   ./demo.sh local    manager runs locally with java (needs JDK 21+ and Maven); hosts in Docker
#   ./demo.sh down     stop and remove everything (hosts return to clean on the next run)
#
# Runs on macOS, Linux, and Windows inside WSL2. Dashboard: https://localhost:8443/
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-docker}"
TOKEN="${SCANNING_ENROLLMENT_TOKEN:-dev-token}"
COMPOSE=(docker compose -f deploy/docker/compose.yml)
MANAGER_LOG=manager.log

ask() { read -r -p "$1 [y/N] " a; [[ "$a" =~ ^[Yy] ]]; }

ensure_docker() {
  if ! command -v docker >/dev/null; then
    echo "Docker is not installed."
    case "$(uname -s)" in
      Linux)
        ask "Install Docker Engine with the official script (https://get.docker.com, needs sudo)?" || exit 1
        curl -fsSL https://get.docker.com | sudo sh
        sudo systemctl enable --now docker
        sudo usermod -aG docker "$USER" || true
        echo "Added $USER to the docker group; until you log in again, this script uses sudo docker."
        ;;
      Darwin)
        command -v brew >/dev/null || { echo "Install Docker Desktop: https://docs.docker.com/desktop/setup/install/mac-install/"; exit 1; }
        ask "Install Docker Desktop with Homebrew (brew install --cask docker)?" || exit 1
        brew install --cask docker
        open -a Docker
        ;;
      *)
        echo "Install Docker Desktop (Windows: winget install Docker.DockerDesktop), enable WSL2"
        echo "integration, then rerun this script inside WSL2."
        exit 1
        ;;
    esac
  fi
  if ! docker info >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
    docker() { sudo docker "$@"; }
  fi
  echo -n "Waiting for the Docker engine"
  for _ in $(seq 60); do docker info >/dev/null 2>&1 && { echo " ok"; return; }; echo -n .; sleep 2; done
  echo; echo "Docker is not running. Start Docker Desktop (accept its terms on first start) and rerun."; exit 1
}

wait_for_manager() {
  echo -n "Waiting for the manager"
  for _ in $(seq 90); do
    curl -skf -o /dev/null https://localhost:8443/ && { echo " ok"; return; }; echo -n .; sleep 2
  done
  echo; echo "Manager did not come up; see its log."; exit 1
}

install_nodes() {
  for n in node-ubuntu node-rocky; do
    echo "== Installing the node on $n (OpenJDK 21 + nmap from the distro repos, ~1 min)"
    docker exec "$n" /installer/install.sh --manager "$1" --token "$TOKEN" | tail -2
  done
}

if [ "$MODE" != down ] && (echo >/dev/tcp/127.0.0.1/8443) 2>/dev/null; then
  echo "Port 8443 is already in use (a manager still running? try ./demo.sh down). Free it and rerun."
  exit 1
fi

case "$MODE" in
  docker)
    ensure_docker
    echo "== Building from source in Docker (first run downloads Maven dependencies, a few minutes)"
    docker build -q --target releases --output type=local,dest=releases . >/dev/null
    SCANNING_ENROLLMENT_TOKEN="$TOKEN" "${COMPOSE[@]}" up -d --build
    wait_for_manager
    install_nodes https://manager:8443
    ;;
  local)
    ensure_docker
    command -v java >/dev/null && command -v mvn >/dev/null \
      || { echo "local mode needs JDK 21+ and Maven (see README, Prerequisites)"; exit 1; }
    echo "== Building with local Maven: node 1.0.0 (installed) and 1.1.0 (upgrade target)"
    mvn -q -DskipTests package
    mvn -q -DskipTests -pl node -am package -Dnode.version=1.1.0
    mkdir -p releases && cp node/target/node-1.0.0.jar node/target/node-1.1.0.jar releases/
    echo "== Starting the manager locally (log: $MANAGER_LOG)"
    SCANNING_ENROLLMENT_TOKEN="$TOKEN" nohup java -jar manager/target/manager-1.0.0.jar > "$MANAGER_LOG" 2>&1 &
    "${COMPOSE[@]}" up -d --build node-ubuntu node-rocky
    wait_for_manager
    install_nodes https://host.docker.internal:8443
    ;;
  down)
    "${COMPOSE[@]}" down -v
    pkill -f manager-1.0.0.jar || true
    exit 0
    ;;
  *) echo "usage: $0 [docker|local|down]"; exit 2 ;;
esac

echo
echo "Done. Dashboard: https://localhost:8443/ (accept the self-signed certificate warning)"
echo "Expected within ~20 s: 2 registered, 2 online. Enrollment token: $TOKEN"
echo "Node log: docker exec node-ubuntu journalctl -u scanning-node -f    Stop: ./demo.sh down"

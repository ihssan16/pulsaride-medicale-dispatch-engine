#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "================================================="
echo "PULSARIDE - VPS deployment"
echo "================================================="

echo "Installing system dependencies..."
apt-get update -qq
apt-get install -y docker.io docker-compose-v2 git curl python3 python3-pip

echo "Starting Docker..."
systemctl enable --now docker

if [ ! -f "$ROOT_DIR/.env" ]; then
  echo "Creating .env from .env.example..."
  cp "$ROOT_DIR/.env.example" "$ROOT_DIR/.env"
fi

echo "Starting Pulsaride stack..."
cd "$ROOT_DIR"
docker compose up --build -d

echo "Waiting for the API health check..."
for attempt in $(seq 1 90); do
  if curl --fail --silent http://localhost:8080/actuator/health >/dev/null; then
    echo "Pulsaride is ready."
    docker compose ps
    echo ""
    echo "API:       http://$(hostname -I | awk '{print $1}'):8080"
    echo "Health:    http://$(hostname -I | awk '{print $1}'):8080/actuator/health"
    echo "Dashboard: http://$(hostname -I | awk '{print $1}'):8080/dashboard-v2.html"
    exit 0
  fi
  sleep 2
done

echo "The API did not become healthy within 180 seconds." >&2
docker compose logs --tail=160 api >&2
exit 1

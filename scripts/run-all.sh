#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Generating simulator data..."
(
  cd "$ROOT_DIR/simulator"
  python3 generate_professionals.py
  python3 generate_requests.py
  python3 generate_scenarios.py
  python3 simulate_behavior.py
)

echo "Building Spring Boot backend..."
(
  cd "$ROOT_DIR/backend"
  mvn clean package
)

echo "Starting PostgreSQL, Redis, and API..."
cd "$ROOT_DIR"
docker compose up --build -d

echo "Waiting for the API health check..."
for attempt in $(seq 1 60); do
  if curl --fail --silent http://localhost:8080/actuator/health >/dev/null; then
    echo "Pulsaride is ready at http://localhost:8080"
    docker compose ps
    exit 0
  fi
  sleep 1
done

echo "The API did not become healthy within 60 seconds." >&2
docker compose logs --tail=120 api >&2
exit 1

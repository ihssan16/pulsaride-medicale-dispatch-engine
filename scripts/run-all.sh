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
docker compose up --build

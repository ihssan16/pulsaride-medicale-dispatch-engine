# Architecture — Pulsaride Medical Dispatch Engine

## Components

- Spring Boot API: exposes dispatch, availability, and persistence-backed state endpoints.
- PostgreSQL: stores professionals and dispatch requests.
- Redis: available for real-time coordination and future queue/session features.
- AI triage provider: local deterministic rules by default, optional Darija Health NLP sidecar, or optional OpenAI provider with the local safety floor.
- Python simulator: generates professionals, patient requests, scenarios, run traces, and metrics.
- Docker Compose: starts PostgreSQL, Redis, and the API.

## Startup Flow

1. Simulator scripts generate JSON files under `simulator/data`.
2. Maven builds the Spring Boot backend.
3. Docker Compose starts infrastructure and API.
4. Flyway creates the database schema.
5. The API imports simulator seed data when the database is empty.
6. `/ai/triage` uses `AI_PROVIDER`/`AI_MODE`: `mock` for local rules, `external`/`darija` for Darija Health NLP, or `openai` for OpenAI structured extraction plus the local safety floor.

## Dispatch Strategy V1

The current backend strategy is intentionally simple and executable:

1. Find available professionals matching the request specialty.
2. Sort by lowest load, then highest experience.
3. Fall back to any available professional.
4. Mark the request as `PROPOSED` or `FAILED`.
5. Track TTFA/TTR timestamps for evaluation.

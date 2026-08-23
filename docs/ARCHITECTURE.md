# Architecture — Pulsaride Medical Dispatch Engine

## Components

- Spring Boot API: exposes dispatch, availability, and persistence-backed state endpoints.
- PostgreSQL: stores professionals and dispatch requests.
- Transactional outbox: stores V2 lifecycle events in `outbox_events` before Kafka publication.
- Outbox Kafka publisher: drains unpublished outbox rows and sends each event to its Kafka topic.
- Triage event consumer: applies `request.triaged.v1` to pending requests and dispatches them with S4.
- API V2 routing: exposes `/api/v2/...` gateway-compatible routes while keeping
  existing V1/demo routes alive.
- Analytics read model: combines metrics, availability, professional load, and
  outbox publication state for `/api/v2/analytics/summary` and `dashboard-v2.html`.
- Redis: available for real-time coordination and future queue/session features.
- AI triage provider: local deterministic rules by default, optional Darija Health NLP sidecar, or optional OpenAI provider; every AI provider response is checked by the local safety floor.
- Python simulator: generates professionals, patient requests, scenarios, run traces, and metrics.
- Docker Compose: starts PostgreSQL, Redis, and the API.

## Startup Flow

1. Simulator scripts generate JSON files under `simulator/data`.
2. Maven builds the Spring Boot backend.
3. Docker Compose starts infrastructure and API.
4. Flyway creates the database schema.
5. The API imports simulator seed data when the database is empty.
6. `/ai/triage` uses `AI_PROVIDER`/`AI_MODE`: `mock` for local rules, `external`/`darija` for Darija Health NLP, or `openai` for OpenAI structured extraction. Darija and OpenAI responses both pass through the local safety floor.
7. Creating a request writes `request.created.v1` to the transactional outbox as
   the V2 Demand Service boundary.
8. Dispatch writes proposal, accept, refusal, timeout, and close events to the
   same outbox so Kafka can publish a complete request lifecycle.
9. External clients can call `/api/v2/...` routes. In this V2 step, those
   routes are aliases to the same Spring services so the contract can stabilize
   before full service extraction.
10. When enabled, Dispatch consumes `request.triaged.v1`, updates the pending
   request priority/specialty, and dispatches it with S4.

## Dispatch Strategy V1

The current backend strategy is intentionally simple and executable:

1. Find available professionals matching the request specialty.
2. Sort by lowest load, then highest experience.
3. Fall back to any available professional.
4. Mark the request as `PROPOSED` or `FAILED`.
5. Track TTFA/TTR timestamps for evaluation.

## V2 Event Runtime

The Spring Boot service now has a transactional outbox table named
`outbox_events`. It records the canonical V2 events from
`docs/events/README.md` while the current local dispatch flow runs normally.

This means V2 can be built incrementally:

1. Demand/Dispatch write durable events first.
2. The Kafka publisher drains unpublished rows when enabled.
3. Dispatch can consume AI triage events without requiring a synchronous API
   call between services.
4. The V2 analytics endpoint exposes the current dispatch state and Kafka/outbox
   backlog in one read model for dashboard and demo usage.
5. Retry, DLT, and replay can consume those events without changing
   the core dispatch transaction.

# Pulsaride V2 Event Contracts

**Story:** V2-202  
**Status:** accepted baseline  

This folder defines the JSON contracts shared by Demand, AI Triage,
Dispatch, Availability, and Analytics.

## Envelope

Every Kafka event uses the same envelope:

| Field | Required | Meaning |
|---|---:|---|
| `eventId` | yes | UUID used for idempotency and DLT replay |
| `eventType` | yes | Topic/event name, for example `request.triaged.v1` |
| `aggregateId` | yes | Aggregate key, usually `requestId`; `professionalId` for availability |
| `correlationId` | yes | Trace ID propagated across the lifecycle |
| `occurredAt` | yes | UTC ISO-8601 timestamp |
| `producer` | yes | Producing service name |
| `schemaVersion` | yes | Integer schema version, starting at `1` |
| `payload` | yes | Event-specific payload |

Kafka message key must be:

- `requestId` for request and dispatch lifecycle events.
- `professionalId` for `availability.changed.v1`.

## Topics Covered

| Event type | Producer | Consumer examples |
|---|---|---|
| `request.created.v1` | Demand Service | AI Triage, Analytics |
| `request.triaged.v1` | AI Triage Service | Dispatch, Analytics |
| `triage.failed.v1` | AI Triage Service | Dispatch fallback, Analytics |
| `dispatch.proposed.v1` | Dispatch Service | Demand, Analytics |
| `dispatch.accepted.v1` | Dispatch Service | Demand, Analytics |
| `dispatch.refused.v1` | Dispatch Service | Dispatch retry, Analytics |
| `dispatch.timed-out.v1` | Dispatch Service | Dispatch retry, Analytics |
| `dispatch.closed.v1` | Dispatch Service | Analytics |
| `availability.changed.v1` | Availability/Dispatch Service | Dispatch, Analytics |

## Validation

Run locally:

```bash
python3 scripts/validate_event_schemas.py
```

The script validates each example against:

1. `docs/events/schemas/event-envelope.schema.json`
2. `docs/events/schemas/<eventType>.payload.schema.json`

CI runs this check on every push/PR to `develop` and `main`.

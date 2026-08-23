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

## Spring Boot Runtime Status

The current Spring Boot V2 runtime writes lifecycle events to the
`outbox_events` table before Kafka publication. The database write and outbox
event happen in the same transaction, so a future Kafka publisher can send the
event without losing the business change.

| Event | Written when |
|---|---|
| `request.created.v1` | a request is created through `/requests` or the legacy create-and-dispatch endpoint |
| `dispatch.proposed.v1` | Dispatch reserves a slot and proposes a professional |
| `dispatch.accepted.v1` | the proposed professional accepts the assignment |
| `dispatch.refused.v1` | the proposed professional refuses and the request goes back to retry |
| `dispatch.timed-out.v1` | the proposal deadline expires and the request goes back to retry |
| `dispatch.closed.v1` | an accepted request is closed and TTFA/TTR are finalized |

`published=false` means the event is still waiting for publication. When
`PULSARIDE_OUTBOX_PUBLISHER_ENABLED=true`, the Spring Boot scheduler drains
unpublished rows in order, publishes the full event envelope to Kafka, then
marks the row as `published=true`.

Kafka routing is intentionally simple:

- topic name = `eventType`
- message key = `aggregateId`
- message value = full JSON envelope with the stored payload

If Kafka is unavailable, the row stays unpublished and will be retried by the
next scheduled run. If Kafka succeeds but the database update fails, the same
event may be sent again after restart; V2 consumers must therefore deduplicate
by `eventId`.

When `PULSARIDE_TRIAGE_CONSUMER_ENABLED=true`, Dispatch consumes
`request.triaged.v1`. For a still-pending request, it updates `urgencyScore`
and `specialtyHint` from the AI payload, records an audit transition, and
dispatches the request with `S4` so the proposal lifecycle continues through
the same outbox/Kafka flow.

The Dispatch consumer records handled Kafka envelopes in the `processed_events`
table using the envelope `eventId` as the primary key. A duplicate
`request.triaged.v1` delivery is skipped before Dispatch is called again, so
the same AI triage event cannot reserve a second slot or create a second
proposal.

If a triage event references a request that does not exist in the local
Dispatch database, the consumer logs a warning, records the event outcome as
`SKIPPED_UNKNOWN_REQUEST`, and skips it. This prevents simulator-only or stale
events from blocking the Kafka consumer group. Unexpected handler failures are
not recorded as processed, so Kafka can retry them.

## Retry, DLT, Replay

V2-205 adds explicit listener retry and DLT routing for Kafka consumers.

- Retry interval: `PULSARIDE_KAFKA_RETRY_INTERVAL_MS` (default `1000` ms)
- Retry attempts after the first delivery: `PULSARIDE_KAFKA_RETRY_MAX_ATTEMPTS`
  (default `3`)
- DLT topic naming: `<source-topic>.dlt`
- DLT partition: always `0`, because DLT topics are single-partition

Malformed JSON or invalid envelopes raise `IllegalArgumentException` and are
sent directly to DLT because retrying cannot repair the payload. Unexpected
handler failures are retried first, then sent to DLT if the attempts are
exhausted.

Manual replay from a local Compose stack:

```bash
MAX_MESSAGES=10 scripts/replay_dlt.sh request.triaged.v1
```

The script reads keyed records from `request.triaged.v1.dlt` and republishes
them to `request.triaged.v1`. Consumers still deduplicate by `eventId`, so
replaying an already processed event is safe.

## Validation

Run locally:

```bash
python3 scripts/validate_event_schemas.py
```

The script validates each example against:

1. `docs/events/schemas/event-envelope.schema.json`
2. `docs/events/schemas/<eventType>.payload.schema.json`

CI runs this check on every push/PR to `develop` and `main`.

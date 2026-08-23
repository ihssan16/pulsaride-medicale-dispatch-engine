# API Contract — Pulsaride Dispatch Engine

Base URL: `http://localhost:8080`

## V2 Gateway Routes

V2 exposes gateway-compatible routes under `/api/v2` while keeping the V1/demo
routes available. The current implementation routes those paths to the same
Spring services; this gives the team a stable external contract before the
remaining services are physically extracted.

| V2 route | Current service |
| --- | --- |
| `GET /api/v2/health` | Health |
| `POST /api/v2/professionals` | Professional/availability setup |
| `GET /api/v2/professionals` | Professional listing |
| `PUT /api/v2/professionals/{id}/status` | Professional lifecycle |
| `POST /api/v2/requests` | Demand Service request creation + `request.created.v1` outbox event |
| `GET /api/v2/requests/{id}` | Demand/request read |
| `POST /api/v2/requests/{id}/triage` | Request-bound AI triage + `request.triaged.v1` outbox event |
| `GET /api/v2/requests/{id}/assignments` | Assignment history |
| `GET /api/v2/requests/{id}/transitions` | FSM audit history |
| `POST /api/v2/dispatch/next?strategy=S4` | Queue-driven dispatch |
| `POST /api/v2/dispatch/{id}?strategy=S4` | Dispatch a pending request |
| `POST /api/v2/dispatch/{id}/accept` | Accept proposal |
| `POST /api/v2/dispatch/{id}/refuse` | Refuse proposal |
| `POST /api/v2/dispatch/{id}/timeout` | Timeout proposal |
| `POST /api/v2/dispatch/{id}/close` | Close accepted request |
| `GET /api/v2/availability` | Availability read model |
| `GET /api/v2/availability/specialties/{tag}` | Availability by specialty |
| `GET /api/v2/metrics/summary` | Live dispatch KPIs |
| `GET /api/v2/analytics/summary` | Combined V2 analytics read model |
| `POST /api/v2/ai/triage` | AI triage provider facade |

## Health

`GET /health`

Returns a small project health payload.

`GET /actuator/health`

Returns Spring Boot health status.

## Professionals

`POST /professionals`

Request body:
```json
{
  "id": "pro_demo",
  "name": "Dr. Demo",
  "specialtyTag": "cardiologie",
  "experienceYears": 8,
  "profileText": "Cardiologue avec experience en palpitations et hypertension.",
  "quotaMaxPerHour": 6,
  "status": "AVAILABLE"
}
```

`GET /professionals`

Optional query parameters:
- `status`: `AVAILABLE`, `PROPOSED`, `BUSY`, `BREAK`, `OFFLINE`

`PUT /professionals/{id}/status`

Request body:
```json
{ "status": "AVAILABLE" }
```

## Availability

`GET /availability`

Returns a live read model of the availability slot pool used by dispatch.

Response shape:
```json
{
  "totalSlots": 3,
  "availableSlots": 2,
  "reservedSlots": 0,
  "busySlots": 1,
  "breakSlots": 0,
  "offlineSlots": 0,
  "availabilityRatePct": 66.67,
  "availableCapacity": 12,
  "specialties": [
    {
      "specialtyTag": "cardiologie",
      "totalSlots": 2,
      "availableSlots": 1,
      "reservedSlots": 0,
      "busySlots": 1,
      "breakSlots": 0,
      "offlineSlots": 0,
      "availabilityRatePct": 50.0,
      "availableCapacity": 6,
      "averageLoad": 0.08,
      "availableSlotIds": ["slot_pro_cardio_2"],
      "availableProfessionalIds": ["pro_cardio_2"]
    }
  ]
}
```

`GET /availability/specialties/{specialtyTag}`

Returns the same availability counters for one specialty tag. The endpoint is case-insensitive for lookup and keeps the requested tag in the response when no slots exist.

Historical aliases:
- `GET /api/availability`
- `GET /api/availability/specialties/{specialtyTag}`

## Dispatch Requests

`POST /requests`

Request body:
```json
{
  "patientId": "patient_demo",
  "patientText": "J ai des palpitations depuis deux jours.",
  "specialtyHint": "cardiologie",
  "urgencyScore": 3
}
```

Creates a pending request and writes it to the Redis priority queue when Redis is available.

`GET /requests/{id}`

Returns one request.

`POST /dispatch/next?strategy=S1`

Dispatches the highest-priority pending request.

Priority order:
- higher `urgencyScore`
- older `createdAt` when urgency is tied

This is the queue-driven path used by API simulations so urgency changes processing order.

`POST /dispatch/{id}?strategy=S1`

Attempts dispatch for an existing pending request.
Dispatch now reserves a separate availability slot first, records a `RESERVED`
state transition, then proposes the request to the linked professional. The final
response status remains `PROPOSED` while the response also includes `assignedSlotId`.

Strategies:
- `S1`: round-robin over currently available slots, rotating from the last S1 proposal
- `S2`: exact specialty tag
- `S3`: classic composite score
- `S4`: mock AI semantic composite score

`POST /dispatch/{id}/accept`

Marks the current proposal as accepted, updates the assignment attempt, moves the reserved slot to `BUSY`, mirrors the professional to `BUSY`, and records a state transition.

`POST /dispatch/{id}/refuse`

Marks the current proposal as refused, puts the slot/professional in `BREAK`, returns the request to `PENDING`, and re-enqueues the request.

`POST /dispatch/{id}/timeout`

Marks the current proposal as timed out, puts the slot/professional in `BREAK`, returns the request to `PENDING`, and re-enqueues the request.

`POST /dispatch/{id}/close`

Closes an accepted request and releases the assigned slot/professional to `AVAILABLE`.

`GET /requests/{id}/assignments`

Returns assignment attempts for a request.

`GET /requests/{id}/transitions`

Returns request FSM transition history.

## Metrics

`GET /metrics/summary`

Returns live V1 metrics: request counts by state, service rate, refusal/timeout/failure rates,
average and P95 TTFA/TTR, average and P95 degraded reassignment delay, and Gini fairness.

`avgDegradedReassignmentMs` and `p95DegradedReassignmentMs` measure the delay between a
persisted refusal/timeout timestamp and the next proposal for the same request. They are
`null` until at least one failed proposal has subsequently been reassigned.

## Dashboard

`GET /dashboard.html`

Returns the lightweight V1 dashboard served by Spring Boot. It polls `/metrics/summary`,
`/availability`, and `/professionals` every 5 seconds to show KPIs, request flow,
availability by specialty, and load per professional.

`GET /dashboard-v2.html`

Returns the V2 analytics dashboard. It polls `/api/v2/analytics/summary`, which combines:

- live dispatch KPIs;
- availability by specialty;
- professional load;
- outbox/Kafka publication counts and recent events.

`GET /api/v2/analytics/summary`

Availability changes are part of the V2 event stream. Professional
creation/status changes and dispatch slot transitions write
`availability.changed.v1` into the transactional outbox, so analytics can show
both request lifecycle events and capacity changes.

Response shape:
```json
{
  "generatedAt": "2026-08-23T12:00:00Z",
  "metrics": {
    "totalRequests": 10,
    "serviceRatePct": 90.0,
    "p95TtfaMs": 1200.0
  },
  "availability": {
    "totalSlots": 5,
    "availableSlots": 3
  },
  "events": {
    "totalEvents": 20,
    "publishedEvents": 18,
    "unpublishedEvents": 2,
    "eventTypes": [
      {
        "eventType": "dispatch.proposed.v1",
        "total": 5,
        "published": 5,
        "unpublished": 0
      }
    ],
    "recentEvents": [
      {
        "eventType": "request.created.v1",
        "aggregateId": "request-id",
        "published": true
      }
    ]
  },
  "professionalLoads": [
    {
      "id": "pro_demo",
      "specialtyTag": "cardiologie",
      "status": "AVAILABLE",
      "load": 0.17
    }
  ]
}
```

## AI Triage

`POST /ai/triage`

Provider selection:

- `AI_PROVIDER=mock` or `AI_MODE=mock`: local deterministic rules.
- `AI_PROVIDER=external|darija` or `AI_MODE=external`: Darija Health NLP sidecar plus local safety floor.
- `AI_PROVIDER=openai` or `AI_MODE=openai`: OpenAI structured extraction plus local safety floor.

Request body:
```json
{
  "text": "j ai mal a la gorge depuis 3 jours, fievre 38.5, enfant 6 ans"
}
```

Response shape:
```json
{
  "symptoms": ["pharyngite", "hyperthermie"],
  "durationDays": 3,
  "severity": 2,
  "ageGroup": "enfant",
  "specialtyHint": "pediatrie",
  "urgencyScore": 2,
  "mode": "mock",
  "confidence": null,
  "urgencyReason": "Local deterministic fallback rules",
  "sourceModel": "pulsaride-rules"
}
```

`POST /api/v2/requests/{id}/triage`

Runs AI triage against the stored request text and writes a durable
`request.triaged.v1` event to the transactional outbox. The legacy
`/ai/triage` endpoint only returns a prediction from raw text; this V2 endpoint
adds the request id required by Kafka consumers.

Response shape is the same as `/ai/triage`. The event payload includes:

```json
{
  "requestId": "req_2026_0001",
  "urgencyScore": 3,
  "specialtyHint": "cardiologie",
  "confidence": 1.0,
  "modelVersion": "pulsaride-rules",
  "ruleVersion": "v2402-r1",
  "requiresReview": true,
  "triggeredRules": ["LOCAL_RULES"]
}
```

In the full Compose/VPS V2 stack this route is not the only way to triage.
When `PULSARIDE_TRIAGE_WORKER_ENABLED=true`, Spring Boot also consumes
`request.created.v1` and automatically runs the same request-bound triage flow.
That worker records:

- `request.triaged.v1` when the provider or local fallback returns a valid triage.
- `triage.failed.v1` when the provider fails and fallback is disabled or cannot recover.

The failure event keeps the request visible to analytics instead of silently
dropping the AI step:

```json
{
  "requestId": "req_2026_0002",
  "failureCode": "AI_UNAVAILABLE",
  "fallbackUrgencyScore": 1,
  "fallbackSpecialtyHint": "generaliste",
  "requiresReview": true,
  "errorMessage": "External AI triage returned HTTP 503: unavailable"
}
```

`POST /api/dispatch-requests/{id}/accept`

Marks a proposed request as accepted and marks the assigned professional as busy.

`POST /api/dispatch-requests/{id}/close`

Closes the request and releases the assigned professional.

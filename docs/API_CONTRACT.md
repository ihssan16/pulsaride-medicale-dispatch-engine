# API Contract — Pulsaride Dispatch Engine

Base URL: `http://localhost:8080`

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
- `S1`: first available
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

## AI Mock Triage

`POST /ai/triage`

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
  "mode": "mock"
}
```

`POST /api/dispatch-requests/{id}/accept`

Marks a proposed request as accepted and marks the assigned professional as busy.

`POST /api/dispatch-requests/{id}/close`

Closes the request and releases the assigned professional.

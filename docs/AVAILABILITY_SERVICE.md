# Availability Service

The availability service is the read model for the professional pool. It helps the team see, before and during dispatch, which professionals can receive a proposal and why a strategy may fail.

## Responsibility

- Count professionals by operational status: `AVAILABLE`, `PROPOSED`, `BUSY`, `BREAK`, `OFFLINE`.
- Group the same counters by `specialtyTag`.
- Expose remaining immediate capacity for available professionals.
- Return the available professional IDs in the same practical order dispatch prefers: lowest load, highest experience, then stable ID order.

## API

- `GET /availability`
- `GET /availability/specialties/{specialtyTag}`
- Historical aliases: `/api/availability` and `/api/availability/specialties/{specialtyTag}`

## Lifecycle Link

Dispatch and availability share the same professional status model:

| Event | Professional status |
|-------|---------------------|
| Manual status update to available | `AVAILABLE` |
| Request proposed | `PROPOSED` |
| Proposal accepted | `BUSY` |
| Request closed | `AVAILABLE` |
| Proposal refused | `BREAK` |
| Proposal timed out | `BREAK` |
| Manual status update to offline | `OFFLINE` |

Only `AVAILABLE` professionals are eligible for matching. This is why the S2 exact-specialty strategy can fail when every professional in the requested specialty is busy, proposed, offline, or on break, even if other specialties still have capacity.

## Example

```bash
curl http://localhost:8080/availability
curl http://localhost:8080/availability/specialties/cardiologie
```

The response includes both global counters and specialty counters so sprint reviews can explain dispatch outcomes with concrete operational state, not only final request metrics.

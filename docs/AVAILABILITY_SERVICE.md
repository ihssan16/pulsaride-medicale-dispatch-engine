# Availability Service

The availability service is the read model for the slot pool. It helps the team see, before and during dispatch, which slots can receive a proposal and why a strategy may fail.

## Responsibility

- Store availability separately from the professional profile in `availability_slots`.
- Count slots by operational status: `AVAILABLE`, `RESERVED`, `BUSY`, `BREAK`, `OFFLINE`.
- Group the same counters by `specialtyTag`.
- Expose remaining immediate capacity for available professionals.
- Return available slot IDs and professional IDs in the same practical order dispatch prefers: lowest load, highest experience, then stable ID order.
- Mirror slot changes back to `Professional.status` only for backward-compatible `/professionals` responses.
- Sync active slots to Redis under `availability:slots:{specialtyTag}` and lock selected slots with `availability:slot:lock:{slotId}`.

## API

- `GET /availability`
- `GET /availability/specialties/{specialtyTag}`
- Historical aliases: `/api/availability` and `/api/availability/specialties/{specialtyTag}`

## Lifecycle Link

Dispatch and availability share the same lifecycle model:

| Event | Slot status | Professional mirror |
|-------|-------------|---------------------|
| Manual status update to available | `AVAILABLE` | `AVAILABLE` |
| Slot selected for a request | `RESERVED` | `PROPOSED` |
| Proposal accepted | `BUSY` | `BUSY` |
| Request closed | `AVAILABLE` | `AVAILABLE` |
| Proposal refused | `BREAK` | `BREAK` |
| Proposal timed out | `BREAK` | `BREAK` |
| Manual status update to offline | `OFFLINE` | `OFFLINE` |

Only `AVAILABLE` slots are eligible for matching. This is why the S2 exact-specialty strategy can fail when every slot in the requested specialty is busy, reserved, offline, or on break, even if other specialties still have capacity.

## Example

```bash
curl http://localhost:8080/availability
curl http://localhost:8080/availability/specialties/cardiologie
```

The response includes both global counters and specialty counters so sprint reviews can explain dispatch outcomes with concrete operational state, not only final request metrics.

## Jira Coverage

- PMDE-26: availability model is `AvailabilitySlot`.
- PMDE-28: profile data and availability data are now separate tables.
- PMDE-29: active slots are synced to Redis.
- PMDE-31/32/33: availability can be queried globally or by specialty and returns available slots.
- PMDE-34: Redis cache/locks are connected to slot state.
- PMDE-43: selected slots are locked in Redis.
- PMDE-44: reservation is saved in `availability_slots.reserved_request_id` and `dispatch_requests.assigned_slot_id`.
- PMDE-45: reservation is recorded as a `RESERVED` request transition before `PROPOSED`.

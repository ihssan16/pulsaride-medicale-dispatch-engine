package com.pulsaride.dispatch.api;

import java.time.OffsetDateTime;

public record RecentEventResponse(
        String eventId,
        String eventType,
        String aggregateId,
        String producer,
        boolean published,
        OffsetDateTime occurredAt,
        OffsetDateTime publishedAt
) {
}

package com.pulsaride.dispatch.api;

import java.util.List;

public record EventPublicationSummaryResponse(
        long totalEvents,
        long publishedEvents,
        long unpublishedEvents,
        List<EventTypeCountResponse> eventTypes,
        List<RecentEventResponse> recentEvents
) {
}

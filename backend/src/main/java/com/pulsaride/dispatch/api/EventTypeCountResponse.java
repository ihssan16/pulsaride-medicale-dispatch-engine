package com.pulsaride.dispatch.api;

public record EventTypeCountResponse(
        String eventType,
        long total,
        long published,
        long unpublished
) {
}

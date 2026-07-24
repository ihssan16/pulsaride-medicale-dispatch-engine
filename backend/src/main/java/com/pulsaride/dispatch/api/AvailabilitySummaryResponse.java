package com.pulsaride.dispatch.api;

import java.util.List;

public record AvailabilitySummaryResponse(
        long totalSlots,
        long availableSlots,
        long reservedSlots,
        long busySlots,
        long breakSlots,
        long offlineSlots,
        double availabilityRatePct,
        int availableCapacity,
        List<SpecialtyAvailabilityResponse> specialties
) {
}

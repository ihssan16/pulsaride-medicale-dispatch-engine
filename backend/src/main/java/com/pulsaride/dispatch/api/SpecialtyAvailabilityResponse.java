package com.pulsaride.dispatch.api;

import java.util.List;

public record SpecialtyAvailabilityResponse(
        String specialtyTag,
        long totalSlots,
        long availableSlots,
        long reservedSlots,
        long busySlots,
        long breakSlots,
        long offlineSlots,
        double availabilityRatePct,
        int availableCapacity,
        double averageLoad,
        List<String> availableSlotIds,
        List<String> availableProfessionalIds
) {
}

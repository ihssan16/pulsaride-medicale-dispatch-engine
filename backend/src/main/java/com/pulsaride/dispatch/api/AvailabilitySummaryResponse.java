package com.pulsaride.dispatch.api;

import java.util.List;

public record AvailabilitySummaryResponse(
        long totalProfessionals,
        long availableProfessionals,
        long proposedProfessionals,
        long busyProfessionals,
        long breakProfessionals,
        long offlineProfessionals,
        double availabilityRatePct,
        int availableCapacity,
        List<SpecialtyAvailabilityResponse> specialties
) {
}

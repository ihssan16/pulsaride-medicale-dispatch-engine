package com.pulsaride.dispatch.api;

import java.util.List;

public record SpecialtyAvailabilityResponse(
        String specialtyTag,
        long totalProfessionals,
        long availableProfessionals,
        long proposedProfessionals,
        long busyProfessionals,
        long breakProfessionals,
        long offlineProfessionals,
        double availabilityRatePct,
        int availableCapacity,
        double averageLoad,
        List<String> availableProfessionalIds
) {
}

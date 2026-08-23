package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;

public record ProfessionalLoadResponse(
        String id,
        String name,
        String specialtyTag,
        ProfessionalStatus status,
        int consultationsToday,
        int quotaMaxPerHour,
        double load
) {
    public static ProfessionalLoadResponse from(Professional professional) {
        return new ProfessionalLoadResponse(
                professional.getId(),
                professional.getName(),
                professional.getSpecialtyTag(),
                professional.getStatus(),
                professional.getConsultationsToday(),
                professional.getQuotaMaxPerHour(),
                professional.getLoad()
        );
    }
}

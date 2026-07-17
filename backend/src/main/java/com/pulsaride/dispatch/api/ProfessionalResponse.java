package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;

public record ProfessionalResponse(
        String id,
        String name,
        String specialtyTag,
        int experienceYears,
        int quotaMaxPerHour,
        ProfessionalStatus status,
        int consultationsToday,
        double load
) {
    public static ProfessionalResponse from(Professional professional) {
        return new ProfessionalResponse(
                professional.getId(),
                professional.getName(),
                professional.getSpecialtyTag(),
                professional.getExperienceYears(),
                professional.getQuotaMaxPerHour(),
                professional.getStatus(),
                professional.getConsultationsToday(),
                professional.getLoad()
        );
    }
}

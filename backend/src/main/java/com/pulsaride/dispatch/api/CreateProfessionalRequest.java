package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.ProfessionalStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProfessionalRequest(
        String id,
        @NotBlank String name,
        @NotBlank String specialtyTag,
        @Min(0) int experienceYears,
        @NotBlank String profileText,
        @Min(1) int quotaMaxPerHour,
        @NotNull ProfessionalStatus status
) {
}

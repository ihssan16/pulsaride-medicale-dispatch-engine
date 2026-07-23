package com.pulsaride.dispatch.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateDispatchRequest(
        @NotBlank String patientId,
        @NotBlank String patientText,
        @NotBlank String specialtyHint,
        @Min(0) @Max(3) int urgencyScore
) {
}

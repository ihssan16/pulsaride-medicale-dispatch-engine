package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.ProfessionalStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateProfessionalStatusRequest(@NotNull ProfessionalStatus status) {
}

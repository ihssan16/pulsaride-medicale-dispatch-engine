package com.pulsaride.dispatch.api;

import jakarta.validation.constraints.NotBlank;

public record TriageRequest(@NotBlank String text) {
}

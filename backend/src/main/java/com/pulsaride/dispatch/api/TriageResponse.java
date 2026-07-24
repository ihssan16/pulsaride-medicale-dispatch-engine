package com.pulsaride.dispatch.api;

import java.util.List;

public record TriageResponse(
        List<String> symptoms,
        Integer durationDays,
        int severity,
        String ageGroup,
        String specialtyHint,
        int urgencyScore,
        String mode
) {
}

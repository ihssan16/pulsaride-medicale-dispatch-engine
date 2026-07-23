package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.Assignment;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import com.pulsaride.dispatch.matching.DispatchStrategy;
import java.time.OffsetDateTime;

public record AssignmentResponse(
        Long id,
        String requestId,
        String professionalId,
        String professionalName,
        DispatchStrategy strategy,
        OffsetDateTime proposedAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime refusedAt,
        OffsetDateTime timedOutAt,
        AssignmentOutcome outcome
) {
    public static AssignmentResponse from(Assignment assignment) {
        var professional = assignment.getProfessional();
        return new AssignmentResponse(
                assignment.getId(),
                assignment.getRequest().getId(),
                professional.getId(),
                professional.getName(),
                assignment.getStrategy(),
                assignment.getProposedAt(),
                assignment.getAcceptedAt(),
                assignment.getRefusedAt(),
                assignment.getTimedOutAt(),
                assignment.getOutcome()
        );
    }
}

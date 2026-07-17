package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.RequestStatus;
import java.time.OffsetDateTime;

public record DispatchRequestResponse(
        String id,
        String patientId,
        String patientText,
        String specialtyHint,
        int urgencyScore,
        RequestStatus status,
        String assignedProfessionalId,
        String assignedProfessionalName,
        OffsetDateTime createdAt,
        OffsetDateTime proposedAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime closedAt,
        Long ttfaMs,
        Long ttrMs,
        String failureReason
) {
    public static DispatchRequestResponse from(DispatchRequest request) {
        var pro = request.getAssignedProfessional();
        return new DispatchRequestResponse(
                request.getId(),
                request.getPatientId(),
                request.getPatientText(),
                request.getSpecialtyHint(),
                request.getUrgencyScore(),
                request.getStatus(),
                pro == null ? null : pro.getId(),
                pro == null ? null : pro.getName(),
                request.getCreatedAt(),
                request.getProposedAt(),
                request.getAcceptedAt(),
                request.getClosedAt(),
                request.getTtfaMs(),
                request.getTtrMs(),
                request.getFailureReason()
        );
    }
}

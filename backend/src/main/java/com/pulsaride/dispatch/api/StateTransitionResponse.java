package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.domain.StateTransition;
import java.time.OffsetDateTime;

public record StateTransitionResponse(
        Long id,
        String requestId,
        RequestStatus fromStatus,
        RequestStatus toStatus,
        String reason,
        OffsetDateTime occurredAt
) {
    public static StateTransitionResponse from(StateTransition transition) {
        return new StateTransitionResponse(
                transition.getId(),
                transition.getRequest().getId(),
                transition.getFromStatus(),
                transition.getToStatus(),
                transition.getReason(),
                transition.getOccurredAt()
        );
    }
}

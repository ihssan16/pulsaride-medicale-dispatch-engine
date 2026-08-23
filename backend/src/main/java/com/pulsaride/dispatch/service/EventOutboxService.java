package com.pulsaride.dispatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.OutboxEvent;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.matching.DispatchStrategy;
import com.pulsaride.dispatch.api.TriageResponse;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EventOutboxService {
    public static final String REQUEST_CREATED = "request.created.v1";
    public static final String REQUEST_TRIAGED = "request.triaged.v1";
    public static final String DISPATCH_PROPOSED = "dispatch.proposed.v1";
    public static final String DISPATCH_ACCEPTED = "dispatch.accepted.v1";
    public static final String DISPATCH_REFUSED = "dispatch.refused.v1";
    public static final String DISPATCH_TIMED_OUT = "dispatch.timed-out.v1";
    public static final String DISPATCH_CLOSED = "dispatch.closed.v1";
    private static final String DEMAND_PRODUCER = "demand-service";
    private static final String AI_TRIAGE_PRODUCER = "ai-triage-service";
    private static final String DISPATCH_PRODUCER = "dispatch-service";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public EventOutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent recordRequestCreated(DispatchRequest request) {
        OffsetDateTime occurredAt = request.getCreatedAt() == null ? OffsetDateTime.now() : request.getCreatedAt();
        Map<String, Object> payload = Map.of(
                "requestId", request.getId(),
                "patientId", request.getPatientId(),
                "freeText", request.getPatientText(),
                "specialtyHint", request.getSpecialtyHint(),
                "initialUrgencyScore", request.getUrgencyScore(),
                "createdAt", occurredAt
        );

        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(REQUEST_CREATED);
        event.setAggregateId(request.getId());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setOccurredAt(occurredAt);
        event.setProducer(DEMAND_PRODUCER);
        event.setSchemaVersion(1);
        event.setPayloadJson(toJson(payload));
        event.setPublished(false);
        return repository.save(event);
    }

    public OutboxEvent recordRequestTriaged(
            DispatchRequest request,
            TriageResponse triage,
            String ruleVersion,
            boolean requiresReview,
            List<String> triggeredRules
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.getId());
        payload.put("urgencyScore", triage.urgencyScore());
        payload.put("specialtyHint", triage.specialtyHint());
        payload.put("confidence", triage.confidence() == null ? 1.0 : triage.confidence());
        payload.put("modelVersion", modelVersion(triage));
        payload.put("ruleVersion", ruleVersion);
        payload.put("requiresReview", requiresReview);
        if (triggeredRules != null && !triggeredRules.isEmpty()) {
            payload.put("triggeredRules", triggeredRules);
        }
        return saveEvent(REQUEST_TRIAGED, request.getId(), AI_TRIAGE_PRODUCER, OffsetDateTime.now(), payload);
    }

    public OutboxEvent recordDispatchProposed(
            DispatchRequest request,
            Professional professional,
            AvailabilitySlot slot,
            DispatchStrategy strategy,
            OffsetDateTime proposedAt,
            OffsetDateTime proposalDeadlineAt,
            int attemptNumber
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.getId());
        payload.put("professionalId", professional.getId());
        payload.put("slotId", slot.getId());
        payload.put("strategy", strategy.name());
        payload.put("proposalDeadlineAt", proposalDeadlineAt);
        payload.put("attemptNumber", attemptNumber);
        return saveEvent(DISPATCH_PROPOSED, request.getId(), DISPATCH_PRODUCER, proposedAt, payload);
    }

    public OutboxEvent recordDispatchAccepted(
            DispatchRequest request,
            Professional professional,
            AvailabilitySlot slot,
            OffsetDateTime acceptedAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.getId());
        payload.put("professionalId", professional.getId());
        payload.put("slotId", slot.getId());
        payload.put("acceptedAt", acceptedAt);
        return saveEvent(DISPATCH_ACCEPTED, request.getId(), DISPATCH_PRODUCER, acceptedAt, payload);
    }

    public OutboxEvent recordDispatchRefused(
            DispatchRequest request,
            Professional professional,
            AvailabilitySlot slot,
            OffsetDateTime refusedAt,
            int attemptNumber
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.getId());
        payload.put("professionalId", professional.getId());
        payload.put("slotId", slot.getId());
        payload.put("refusalCode", "MANUAL_REFUSAL");
        payload.put("refusedAt", refusedAt);
        payload.put("attemptNumber", attemptNumber);
        return saveEvent(DISPATCH_REFUSED, request.getId(), DISPATCH_PRODUCER, refusedAt, payload);
    }

    public OutboxEvent recordDispatchTimedOut(
            DispatchRequest request,
            Professional professional,
            AvailabilitySlot slot,
            OffsetDateTime timedOutAt,
            int attemptNumber,
            boolean nextRetryAllowed
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.getId());
        payload.put("professionalId", professional.getId());
        payload.put("slotId", slot.getId());
        payload.put("timedOutAt", timedOutAt);
        payload.put("attemptNumber", attemptNumber);
        payload.put("nextRetryAllowed", nextRetryAllowed);
        return saveEvent(DISPATCH_TIMED_OUT, request.getId(), DISPATCH_PRODUCER, timedOutAt, payload);
    }

    public OutboxEvent recordDispatchClosed(
            DispatchRequest request,
            Professional professional,
            AvailabilitySlot slot,
            OffsetDateTime closedAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.getId());
        payload.put("professionalId", professional.getId());
        payload.put("slotId", slot.getId());
        payload.put("closedAt", closedAt);
        payload.put("resolutionStatus", "COMPLETED");
        if (request.getTtfaMs() != null) {
            payload.put("ttfaMs", request.getTtfaMs());
        }
        if (request.getTtrMs() != null) {
            payload.put("ttrMs", request.getTtrMs());
        }
        return saveEvent(DISPATCH_CLOSED, request.getId(), DISPATCH_PRODUCER, closedAt, payload);
    }

    private OutboxEvent saveEvent(
            String eventType,
            String aggregateId,
            String producer,
            OffsetDateTime occurredAt,
            Map<String, Object> payload
    ) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setOccurredAt(occurredAt == null ? OffsetDateTime.now() : occurredAt);
        event.setProducer(producer);
        event.setSchemaVersion(1);
        event.setPayloadJson(toJson(payload));
        event.setPublished(false);
        return repository.save(event);
    }

    private String modelVersion(TriageResponse triage) {
        if (triage.sourceModel() != null && !triage.sourceModel().isBlank()) {
            return triage.sourceModel();
        }
        if (triage.mode() != null && !triage.mode().isBlank()) {
            return triage.mode();
        }
        return "unknown";
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize outbox event payload", ex);
        }
    }
}

package com.pulsaride.dispatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.OutboxEvent;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EventOutboxService {
    public static final String REQUEST_CREATED = "request.created.v1";
    private static final String DEMAND_PRODUCER = "demand-service";

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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize outbox event payload", ex);
        }
    }
}

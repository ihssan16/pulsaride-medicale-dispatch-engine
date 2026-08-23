package com.pulsaride.dispatch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pulsaride.triage.worker.enabled", havingValue = "true")
public class RequestCreatedEventConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestCreatedEventConsumer.class);
    private static final String EVENT_TYPE = "request.created.v1";

    private final RequestTriageService requestTriageService;
    private final DispatchRequestRepository requestRepository;
    private final ObjectMapper objectMapper;
    private final ProcessedEventService processedEventService;

    public RequestCreatedEventConsumer(
            RequestTriageService requestTriageService,
            DispatchRequestRepository requestRepository,
            ObjectMapper objectMapper,
            ProcessedEventService processedEventService
    ) {
        this.requestTriageService = requestTriageService;
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
        this.processedEventService = processedEventService;
    }

    @KafkaListener(
            topics = "${pulsaride.triage.worker.topic:request.created.v1}",
            groupId = "${pulsaride.triage.worker.group-id:pulsaride-ai-triage}"
    )
    public void onMessage(String message) {
        CreatedEvent event = parse(message);
        if (event == null) {
            return;
        }
        if (!requestRepository.existsById(event.requestId())) {
            processedEventService.processOnce(event.eventId(), event.eventType(), event.aggregateId(), () -> {
                LOGGER.warn("Skipping created event for unknown requestId={}", event.requestId());
                return "SKIPPED_UNKNOWN_REQUEST";
            });
            return;
        }
        processedEventService.processOnce(event.eventId(), event.eventType(), event.aggregateId(), () ->
                requestTriageService.triageAndPublishOutcome(event.requestId())
        );
    }

    private CreatedEvent parse(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String eventType = requiredText(envelope, "eventType");
            if (!EVENT_TYPE.equals(eventType)) {
                LOGGER.debug("Ignoring Kafka event type {} on request-created triage worker", eventType);
                return null;
            }

            JsonNode payload = envelope.path("payload");
            if (!payload.isObject()) {
                throw new IllegalArgumentException("request.created.v1 missing payload object");
            }
            return new CreatedEvent(
                    requiredText(envelope, "eventId"),
                    eventType,
                    requiredText(envelope, "aggregateId"),
                    requiredText(payload, "requestId")
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid request.created.v1 JSON", ex);
        }
    }

    private String requiredText(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("request.created.v1 missing " + field);
        }
        return node.get(field).asText();
    }

    private record CreatedEvent(
            String eventId,
            String eventType,
            String aggregateId,
            String requestId
    ) {
    }
}

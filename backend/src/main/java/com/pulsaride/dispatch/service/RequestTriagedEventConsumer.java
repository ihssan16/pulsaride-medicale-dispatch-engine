package com.pulsaride.dispatch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pulsaride.triage.consumer.enabled", havingValue = "true")
public class RequestTriagedEventConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestTriagedEventConsumer.class);
    private static final String EVENT_TYPE = "request.triaged.v1";

    private final DispatchService dispatchService;
    private final ObjectMapper objectMapper;

    public RequestTriagedEventConsumer(DispatchService dispatchService, ObjectMapper objectMapper) {
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${pulsaride.triage.consumer.topic:request.triaged.v1}",
            groupId = "${pulsaride.triage.consumer.group-id:pulsaride-dispatch}"
    )
    public void onMessage(String message) {
        TriageEvent event = parse(message);
        if (event == null) {
            return;
        }
        dispatchService.applyTriageAndDispatch(
                event.requestId(),
                event.urgencyScore(),
                event.specialtyHint(),
                event.summary()
        );
    }

    private TriageEvent parse(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            String eventType = requiredText(envelope, "eventType");
            if (!EVENT_TYPE.equals(eventType)) {
                LOGGER.debug("Ignoring Kafka event type {} on triage consumer", eventType);
                return null;
            }

            JsonNode payload = envelope.path("payload");
            if (!payload.isObject()) {
                throw new IllegalArgumentException("request.triaged.v1 missing payload object");
            }
            String requestId = requiredText(payload, "requestId");
            int urgencyScore = payload.path("urgencyScore").asInt();
            String specialtyHint = requiredText(payload, "specialtyHint");
            double confidence = payload.path("confidence").asDouble();
            String modelVersion = requiredText(payload, "modelVersion");
            boolean requiresReview = payload.path("requiresReview").asBoolean();
            return new TriageEvent(requestId, urgencyScore, specialtyHint, confidence, modelVersion, requiresReview);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid request.triaged.v1 JSON", ex);
        }
    }

    private String requiredText(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("request.triaged.v1 missing " + field);
        }
        return node.get(field).asText();
    }

    private record TriageEvent(
            String requestId,
            int urgencyScore,
            String specialtyHint,
            double confidence,
            String modelVersion,
            boolean requiresReview
    ) {
        String summary() {
            return "AI triage applied: model=" + modelVersion
                    + ", confidence=" + confidence
                    + ", requiresReview=" + requiresReview;
        }
    }
}

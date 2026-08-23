package com.pulsaride.dispatch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulsaride.dispatch.domain.OutboxEvent;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "pulsaride.outbox.publisher.enabled", havingValue = "true")
public class OutboxKafkaPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxKafkaPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public OutboxKafkaPublisher(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${pulsaride.outbox.publisher.batch-size:50}") int batchSize
    ) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${pulsaride.outbox.publisher.interval-ms:5000}")
    @Transactional
    public int publishPending() {
        var pending = repository.findByPublishedFalseOrderByOccurredAtAsc(PageRequest.of(0, batchSize));
        int publishedCount = 0;
        for (OutboxEvent event : pending) {
            try {
                publishOne(event);
                publishedCount++;
            } catch (RuntimeException ex) {
                LOGGER.warn(
                        "Outbox publish failed for eventId={} eventType={}: {}",
                        event.getEventId(),
                        event.getEventType(),
                        ex.getMessage()
                );
            }
        }
        return publishedCount;
    }

    private void publishOne(OutboxEvent event) {
        String envelope = toEnvelopeJson(event);
        try {
            kafkaTemplate
                    .send(event.getEventType(), event.getAggregateId(), envelope)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka publish failed", ex);
        }

        event.setPublished(true);
        event.setPublishedAt(OffsetDateTime.now());
        repository.save(event);
    }

    private String toEnvelopeJson(OutboxEvent event) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", event.getEventId());
        envelope.put("eventType", event.getEventType());
        envelope.put("aggregateId", event.getAggregateId());
        envelope.put("correlationId", event.getCorrelationId());
        envelope.put("occurredAt", event.getOccurredAt().toString());
        envelope.put("producer", event.getProducer());
        envelope.put("schemaVersion", event.getSchemaVersion());
        try {
            envelope.set("payload", objectMapper.readTree(event.getPayloadJson()));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not build Kafka event envelope", ex);
        }
    }
}

package com.pulsaride.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.OutboxEvent;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxKafkaPublisherTests {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxKafkaPublisher publisher = new OutboxKafkaPublisher(
            repository,
            kafkaTemplate,
            new ObjectMapper(),
            10
    );

    @Test
    void publishesEnvelopeAndMarksOutboxRowAsPublished() {
        var event = outboxEvent();
        given(repository.findByPublishedFalseOrderByOccurredAtAsc(any(Pageable.class))).willReturn(List.of(event));
        given(kafkaTemplate.send(eq("request.created.v1"), eq("req_1"), any(String.class)))
                .willReturn(CompletableFuture.completedFuture(null));

        int published = publisher.publishPending();

        assertThat(published).isEqualTo(1);
        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isNotNull();
        verify(repository).save(event);
        verify(kafkaTemplate).send(
                eq("request.created.v1"),
                eq("req_1"),
                org.mockito.ArgumentMatchers.contains("\"payload\":{\"requestId\":\"req_1\"}")
        );
    }

    @Test
    void keepsOutboxRowUnpublishedWhenKafkaSendFails() {
        var event = outboxEvent();
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        given(repository.findByPublishedFalseOrderByOccurredAtAsc(any(Pageable.class))).willReturn(List.of(event));
        given(kafkaTemplate.send(eq("request.created.v1"), eq("req_1"), any(String.class))).willReturn(failed);

        int published = publisher.publishPending();

        assertThat(published).isZero();
        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
        verify(repository, never()).save(event);
    }

    private OutboxEvent outboxEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId("evt_1");
        event.setEventType("request.created.v1");
        event.setAggregateId("req_1");
        event.setCorrelationId("corr_1");
        event.setOccurredAt(OffsetDateTime.parse("2026-08-23T12:00:00Z"));
        event.setProducer("demand-service");
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"requestId\":\"req_1\"}");
        event.setPublished(false);
        return event;
    }
}

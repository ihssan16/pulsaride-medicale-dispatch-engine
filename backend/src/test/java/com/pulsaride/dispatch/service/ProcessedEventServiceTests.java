package com.pulsaride.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulsaride.dispatch.repository.ProcessedEventRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProcessedEventServiceTests {
    @Autowired
    private ProcessedEventService processedEventService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    void processOnceStoresEventAndSkipsDuplicateDelivery() {
        AtomicInteger handlerCalls = new AtomicInteger();

        boolean firstDelivery = processedEventService.processOnce(
                "evt_once",
                "request.triaged.v1",
                "req_once",
                () -> {
                    handlerCalls.incrementAndGet();
                    return "PROCESSED";
                }
        );
        boolean duplicateDelivery = processedEventService.processOnce(
                "evt_once",
                "request.triaged.v1",
                "req_once",
                () -> {
                    handlerCalls.incrementAndGet();
                    return "PROCESSED";
                }
        );

        assertThat(firstDelivery).isTrue();
        assertThat(duplicateDelivery).isFalse();
        assertThat(handlerCalls).hasValue(1);
        assertThat(processedEventRepository.findById("evt_once"))
                .get()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("request.triaged.v1");
                    assertThat(event.getAggregateId()).isEqualTo("req_once");
                    assertThat(event.getOutcome()).isEqualTo("PROCESSED");
                    assertThat(event.getProcessedAt()).isNotNull();
                });
    }

    @Test
    void processOnceRollsBackWhenHandlerFailsSoKafkaCanRetry() {
        assertThatThrownBy(() -> processedEventService.processOnce(
                "evt_retry",
                "request.triaged.v1",
                "req_retry",
                () -> {
                    throw new IllegalStateException("temporary downstream failure");
                }
        )).isInstanceOf(IllegalStateException.class);

        assertThat(processedEventRepository.existsById("evt_retry")).isFalse();
    }
}

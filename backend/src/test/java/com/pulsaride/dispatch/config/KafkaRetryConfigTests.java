package com.pulsaride.dispatch.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaRetryConfigTests {
    private final KafkaRetryConfig config = new KafkaRetryConfig();

    @Test
    void buildsDefaultErrorHandlerForKafkaListeners() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

        DefaultErrorHandler errorHandler = config.kafkaErrorHandler(kafkaTemplate, 100L, 2L);

        assertThat(errorHandler).isNotNull();
    }

    @Test
    void declaresThreePartitionLifecycleTopics() {
        assertTopic(config.requestCreatedTopic(), "request.created.v1", 3);
        assertTopic(config.requestTriagedTopic(), "request.triaged.v1", 3);
        assertTopic(config.triageFailedTopic(), "triage.failed.v1", 3);
        assertTopic(config.dispatchProposedTopic(), "dispatch.proposed.v1", 3);
        assertTopic(config.dispatchAcceptedTopic(), "dispatch.accepted.v1", 3);
        assertTopic(config.dispatchRefusedTopic(), "dispatch.refused.v1", 3);
        assertTopic(config.dispatchTimedOutTopic(), "dispatch.timed-out.v1", 3);
        assertTopic(config.dispatchClosedTopic(), "dispatch.closed.v1", 3);
        assertTopic(config.availabilityChangedTopic(), "availability.changed.v1", 3);
    }

    @Test
    void declaresSinglePartitionDltTopics() {
        assertTopic(config.requestCreatedDltTopic(), "request.created.v1.dlt", 1);
        assertTopic(config.requestTriagedDltTopic(), "request.triaged.v1.dlt", 1);
        assertTopic(config.dispatchProposedDltTopic(), "dispatch.proposed.v1.dlt", 1);
    }

    private void assertTopic(NewTopic topic, String name, int partitions) {
        assertThat(topic.name()).isEqualTo(name);
        assertThat(topic.numPartitions()).isEqualTo(partitions);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}

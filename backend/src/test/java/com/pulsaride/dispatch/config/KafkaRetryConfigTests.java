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
    void declaresSinglePartitionDltTopics() {
        assertTopic(config.requestCreatedDltTopic(), "request.created.v1.dlt");
        assertTopic(config.requestTriagedDltTopic(), "request.triaged.v1.dlt");
        assertTopic(config.dispatchProposedDltTopic(), "dispatch.proposed.v1.dlt");
    }

    private void assertTopic(NewTopic topic, String name) {
        assertThat(topic.name()).isEqualTo(name);
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }
}

package com.pulsaride.dispatch.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaRetryConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaRetryConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${pulsaride.kafka.retry.interval-ms:1000}") long retryIntervalMs,
            @Value("${pulsaride.kafka.retry.max-attempts:3}") long maxAttempts
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", 0)
        );
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, maxAttempts)
        );
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) -> LOGGER.warn(
                "Kafka listener failure topic={} partition={} offset={} deliveryAttempt={} reason={}",
                record.topic(),
                record.partition(),
                record.offset(),
                deliveryAttempt,
                exception.getMessage()
        ));
        return errorHandler;
    }

    @Bean
    public NewTopic requestCreatedTopic() {
        return lifecycleTopic("request.created.v1");
    }

    @Bean
    public NewTopic requestTriagedTopic() {
        return lifecycleTopic("request.triaged.v1");
    }

    @Bean
    public NewTopic triageFailedTopic() {
        return lifecycleTopic("triage.failed.v1");
    }

    @Bean
    public NewTopic dispatchProposedTopic() {
        return lifecycleTopic("dispatch.proposed.v1");
    }

    @Bean
    public NewTopic dispatchAcceptedTopic() {
        return lifecycleTopic("dispatch.accepted.v1");
    }

    @Bean
    public NewTopic dispatchRefusedTopic() {
        return lifecycleTopic("dispatch.refused.v1");
    }

    @Bean
    public NewTopic dispatchTimedOutTopic() {
        return lifecycleTopic("dispatch.timed-out.v1");
    }

    @Bean
    public NewTopic dispatchClosedTopic() {
        return lifecycleTopic("dispatch.closed.v1");
    }

    @Bean
    public NewTopic availabilityChangedTopic() {
        return lifecycleTopic("availability.changed.v1");
    }

    @Bean
    public NewTopic requestCreatedDltTopic() {
        return dltTopic("request.created.v1.dlt");
    }

    @Bean
    public NewTopic requestTriagedDltTopic() {
        return dltTopic("request.triaged.v1.dlt");
    }

    @Bean
    public NewTopic dispatchProposedDltTopic() {
        return dltTopic("dispatch.proposed.v1.dlt");
    }

    private NewTopic lifecycleTopic(String name) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(1)
                .build();
    }

    private NewTopic dltTopic(String name) {
        return TopicBuilder.name(name)
                .partitions(1)
                .replicas(1)
                .build();
    }
}

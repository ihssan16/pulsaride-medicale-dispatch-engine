package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.domain.ProcessedEvent;
import com.pulsaride.dispatch.repository.ProcessedEventRepository;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessedEventService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessedEventService.class);

    private final ProcessedEventRepository repository;

    public ProcessedEventService(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean processOnce(String eventId, String eventType, String aggregateId, EventHandler handler) {
        if (repository.existsById(eventId)) {
            LOGGER.info("Skipping duplicate eventId={} eventType={}", eventId, eventType);
            return false;
        }

        ProcessedEvent processedEvent = new ProcessedEvent();
        processedEvent.setEventId(eventId);
        processedEvent.setEventType(eventType);
        processedEvent.setAggregateId(aggregateId);
        processedEvent.setProcessedAt(OffsetDateTime.now());
        processedEvent.setOutcome("IN_PROGRESS");
        try {
            repository.saveAndFlush(processedEvent);
        } catch (DataIntegrityViolationException ex) {
            LOGGER.info("Skipping concurrently processed eventId={} eventType={}", eventId, eventType);
            return false;
        }

        String outcome = handler.handle();
        processedEvent.setProcessedAt(OffsetDateTime.now());
        processedEvent.setOutcome(outcome);
        repository.saveAndFlush(processedEvent);
        return true;
    }

    @FunctionalInterface
    public interface EventHandler {
        String handle();
    }
}

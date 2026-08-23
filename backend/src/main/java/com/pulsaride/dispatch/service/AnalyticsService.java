package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.AnalyticsSummaryResponse;
import com.pulsaride.dispatch.api.EventPublicationSummaryResponse;
import com.pulsaride.dispatch.api.EventTypeCountResponse;
import com.pulsaride.dispatch.api.ProfessionalLoadResponse;
import com.pulsaride.dispatch.api.RecentEventResponse;
import com.pulsaride.dispatch.domain.OutboxEvent;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {
    private static final int RECENT_EVENT_LIMIT = 10;

    private final MetricsService metricsService;
    private final AvailabilityService availabilityService;
    private final OutboxEventRepository outboxEventRepository;
    private final ProfessionalRepository professionalRepository;

    public AnalyticsService(
            MetricsService metricsService,
            AvailabilityService availabilityService,
            OutboxEventRepository outboxEventRepository,
            ProfessionalRepository professionalRepository
    ) {
        this.metricsService = metricsService;
        this.availabilityService = availabilityService;
        this.outboxEventRepository = outboxEventRepository;
        this.professionalRepository = professionalRepository;
    }

    @Transactional
    public AnalyticsSummaryResponse summary() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        List<ProfessionalLoadResponse> professionalLoads = professionalRepository.findAll()
                .stream()
                .map(ProfessionalLoadResponse::from)
                .sorted(Comparator
                        .comparingDouble(ProfessionalLoadResponse::load)
                        .reversed()
                        .thenComparing(ProfessionalLoadResponse::id))
                .toList();

        return new AnalyticsSummaryResponse(
                OffsetDateTime.now(),
                metricsService.summary(),
                availabilityService.summary(),
                summarizeEvents(events),
                professionalLoads
        );
    }

    private EventPublicationSummaryResponse summarizeEvents(List<OutboxEvent> events) {
        long published = events.stream().filter(OutboxEvent::isPublished).count();
        List<EventTypeCountResponse> eventTypes = events.stream()
                .collect(Collectors.groupingBy(OutboxEvent::getEventType))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> countEventType(entry.getKey(), entry.getValue()))
                .toList();
        List<RecentEventResponse> recentEvents = events.stream()
                .sorted(Comparator
                        .comparing(OutboxEvent::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(OutboxEvent::getEventId))
                .limit(RECENT_EVENT_LIMIT)
                .map(this::toRecentEvent)
                .toList();

        return new EventPublicationSummaryResponse(
                events.size(),
                published,
                events.size() - published,
                eventTypes,
                recentEvents
        );
    }

    private EventTypeCountResponse countEventType(String eventType, List<OutboxEvent> events) {
        long published = events.stream().filter(OutboxEvent::isPublished).count();
        return new EventTypeCountResponse(
                eventType,
                events.size(),
                published,
                events.size() - published
        );
    }

    private RecentEventResponse toRecentEvent(OutboxEvent event) {
        return new RecentEventResponse(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateId(),
                event.getProducer(),
                event.isPublished(),
                event.getOccurredAt(),
                event.getPublishedAt()
        );
    }
}

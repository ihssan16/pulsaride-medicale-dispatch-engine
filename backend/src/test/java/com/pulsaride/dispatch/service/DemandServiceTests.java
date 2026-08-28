package com.pulsaride.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.pulsaride.dispatch.api.CreateDispatchRequest;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class DemandServiceTests {
    @Autowired
    private DemandService demandService;

    @Autowired
    private DispatchRequestRepository requestRepository;

    @Autowired
    private StateTransitionRepository transitionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private DispatchRedisService redisService;

    @Test
    void createsPendingRequestAndPublishesRequestCreatedEvent() {
        var created = demandService.create(new CreateDispatchRequest(
                "patient_demand",
                "Douleur thoracique avec essoufflement",
                "cardiologie",
                3
        ));

        assertThat(created.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(requestRepository.findById(created.getId())).isPresent();
        assertThat(transitionRepository.findByRequestIdOrderByOccurredAtAsc(created.getId()))
                .singleElement()
                .satisfies(transition -> {
                    assertThat(transition.getFromStatus()).isNull();
                    assertThat(transition.getToStatus()).isEqualTo(RequestStatus.PENDING);
                    assertThat(transition.getReason()).isEqualTo("Request created");
                });
        assertThat(outboxEventRepository.findByAggregateIdOrderByOccurredAtAsc(created.getId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(EventOutboxService.REQUEST_CREATED);
                    assertThat(event.getProducer()).isEqualTo("demand-service");
                    assertThat(event.isPublished()).isFalse();
                    assertThat(event.getPayloadJson()).contains(
                            "\"patientId\":\"patient_demand\"",
                            "\"initialUrgencyScore\":3"
                    );
                });
        verify(redisService).enqueue(created);
    }

    @Test
    void automaticallyTriagesRequestWhenSpecialtyOrUrgencyIsMissing() {
        var created = demandService.create(new CreateDispatchRequest(
                "patient_auto_triage",
                "Douleur thoracique avec essoufflement",
                null,
                null
        ));

        assertThat(created.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(created.getSpecialtyHint()).isEqualTo("cardiologie");
        assertThat(created.getUrgencyScore()).isEqualTo(3);
        assertThat(transitionRepository.findByRequestIdOrderByOccurredAtAsc(created.getId()))
                .hasSize(2)
                .anySatisfy(transition -> assertThat(transition.getReason())
                        .isEqualTo("Automatic AI triage applied during request creation"));
        assertThat(outboxEventRepository.findByAggregateIdOrderByOccurredAtAsc(created.getId()))
                .hasSize(2)
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo(EventOutboxService.REQUEST_TRIAGED);
                    assertThat(event.getProducer()).isEqualTo("ai-triage-service");
                    assertThat(event.getPayloadJson()).contains(
                            "\"urgencyScore\":3",
                            "\"specialtyHint\":\"cardiologie\"",
                            "\"triggeredRules\":[\"LOCAL_RULES\"]"
                    );
                });
        verify(redisService).enqueue(created);
    }
}

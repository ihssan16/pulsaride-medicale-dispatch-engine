package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.CreateDispatchRequest;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.domain.StateTransition;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemandService {
    private final DispatchRequestRepository requestRepository;
    private final StateTransitionRepository transitionRepository;
    private final EventOutboxService eventOutboxService;
    private final DispatchRedisService redisService;

    public DemandService(
            DispatchRequestRepository requestRepository,
            StateTransitionRepository transitionRepository,
            EventOutboxService eventOutboxService,
            DispatchRedisService redisService
    ) {
        this.requestRepository = requestRepository;
        this.transitionRepository = transitionRepository;
        this.eventOutboxService = eventOutboxService;
        this.redisService = redisService;
    }

    @Transactional
    public DispatchRequest create(CreateDispatchRequest command) {
        DispatchRequest request = new DispatchRequest();
        request.setId(UUID.randomUUID().toString());
        request.setPatientId(command.patientId());
        request.setPatientText(command.patientText());
        request.setSpecialtyHint(command.specialtyHint());
        request.setUrgencyScore(command.urgencyScore());
        request.setCreatedAt(OffsetDateTime.now());
        request.setStatus(RequestStatus.PENDING);

        DispatchRequest saved = requestRepository.save(request);
        recordTransition(saved, null, RequestStatus.PENDING, "Request created");
        eventOutboxService.recordRequestCreated(saved);
        redisService.enqueue(saved);
        return saved;
    }

    private void recordTransition(
            DispatchRequest request,
            RequestStatus fromStatus,
            RequestStatus toStatus,
            String reason
    ) {
        StateTransition transition = new StateTransition();
        transition.setRequest(request);
        transition.setFromStatus(fromStatus);
        transition.setToStatus(toStatus);
        transition.setReason(reason);
        transition.setOccurredAt(OffsetDateTime.now());
        transitionRepository.save(transition);
    }
}

package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.ai.AiTriageService;
import com.pulsaride.dispatch.api.CreateDispatchRequest;
import com.pulsaride.dispatch.api.TriageResponse;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.domain.StateTransition;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemandService {
    private static final String RULE_VERSION = "v2402-r1";
    private static final double REVIEW_CONFIDENCE_THRESHOLD = 0.75;

    private final DispatchRequestRepository requestRepository;
    private final StateTransitionRepository transitionRepository;
    private final EventOutboxService eventOutboxService;
    private final DispatchRedisService redisService;
    private final AiTriageService aiTriageService;

    public DemandService(
            DispatchRequestRepository requestRepository,
            StateTransitionRepository transitionRepository,
            EventOutboxService eventOutboxService,
            DispatchRedisService redisService,
            AiTriageService aiTriageService
    ) {
        this.requestRepository = requestRepository;
        this.transitionRepository = transitionRepository;
        this.eventOutboxService = eventOutboxService;
        this.redisService = redisService;
        this.aiTriageService = aiTriageService;
    }

    @Transactional
    public DispatchRequest create(CreateDispatchRequest command) {
        TriageResponse automaticTriage = shouldAutoTriage(command) ? aiTriageService.triage(command.patientText()) : null;

        DispatchRequest request = new DispatchRequest();
        request.setId(UUID.randomUUID().toString());
        request.setPatientId(command.patientId());
        request.setPatientText(command.patientText());
        request.setSpecialtyHint(resolveSpecialtyHint(command, automaticTriage));
        request.setUrgencyScore(resolveUrgencyScore(command, automaticTriage));
        request.setCreatedAt(OffsetDateTime.now());
        request.setStatus(RequestStatus.PENDING);

        DispatchRequest saved = requestRepository.save(request);
        recordTransition(saved, null, RequestStatus.PENDING, "Request created");
        eventOutboxService.recordRequestCreated(saved);
        if (automaticTriage != null) {
            eventOutboxService.recordRequestTriaged(
                    saved,
                    automaticTriage,
                    RULE_VERSION,
                    requiresReview(automaticTriage),
                    triggeredRules(automaticTriage)
            );
            recordTransition(saved, RequestStatus.PENDING, RequestStatus.PENDING, "Automatic AI triage applied during request creation");
        }
        redisService.enqueue(saved);
        return saved;
    }

    private boolean shouldAutoTriage(CreateDispatchRequest command) {
        return command.specialtyHint() == null
                || command.specialtyHint().isBlank()
                || command.urgencyScore() == null;
    }

    private String resolveSpecialtyHint(CreateDispatchRequest command, TriageResponse automaticTriage) {
        if (command.specialtyHint() != null && !command.specialtyHint().isBlank()) {
            return command.specialtyHint();
        }
        if (automaticTriage != null && automaticTriage.specialtyHint() != null && !automaticTriage.specialtyHint().isBlank()) {
            return automaticTriage.specialtyHint();
        }
        return "generaliste";
    }

    private int resolveUrgencyScore(CreateDispatchRequest command, TriageResponse automaticTriage) {
        if (command.urgencyScore() != null) {
            return command.urgencyScore();
        }
        return automaticTriage == null ? 0 : automaticTriage.urgencyScore();
    }

    private boolean requiresReview(TriageResponse triage) {
        return triage.urgencyScore() >= 3
                || triage.confidence() == null
                || triage.confidence() < REVIEW_CONFIDENCE_THRESHOLD;
    }

    private List<String> triggeredRules(TriageResponse triage) {
        if (triage.mode() != null && triage.mode().contains("safety-floor")) {
            return List.of("SAFETY_FLOOR");
        }
        if ("pulsaride-rules".equals(triage.sourceModel())) {
            return List.of("LOCAL_RULES");
        }
        return List.of();
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

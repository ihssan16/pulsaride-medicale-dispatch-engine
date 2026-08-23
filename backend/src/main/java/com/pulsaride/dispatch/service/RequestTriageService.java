package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.ai.AiTriageService;
import com.pulsaride.dispatch.api.TriageResponse;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestTriageService {
    private static final String RULE_VERSION = "v2402-r1";
    private static final double REVIEW_CONFIDENCE_THRESHOLD = 0.75;

    private final DispatchRequestRepository requestRepository;
    private final AiTriageService aiTriageService;
    private final EventOutboxService eventOutboxService;

    public RequestTriageService(
            DispatchRequestRepository requestRepository,
            AiTriageService aiTriageService,
            EventOutboxService eventOutboxService
    ) {
        this.requestRepository = requestRepository;
        this.aiTriageService = aiTriageService;
        this.eventOutboxService = eventOutboxService;
    }

    @Transactional
    public TriageResponse triageAndPublish(String requestId) {
        DispatchRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));

        TriageResponse triage = aiTriageService.triage(request.getPatientText());
        eventOutboxService.recordRequestTriaged(
                request,
                triage,
                RULE_VERSION,
                requiresReview(triage),
                triggeredRules(triage)
        );
        return triage;
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
}

package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.ai.AiTriageService;
import com.pulsaride.dispatch.api.TriageResponse;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
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
        DispatchRequest request = findRequest(requestId);
        TriageResponse triage = aiTriageService.triage(request.getPatientText());
        publishTriaged(request, triage);
        return triage;
    }

    @Transactional
    public String triageAndPublishOutcome(String requestId) {
        DispatchRequest request = findRequest(requestId);
        try {
            TriageResponse triage = aiTriageService.triage(request.getPatientText());
            publishTriaged(request, triage);
            return "TRIAGED";
        } catch (RuntimeException ex) {
            eventOutboxService.recordTriageFailed(
                    request,
                    failureCode(ex),
                    request.getUrgencyScore(),
                    fallbackSpecialtyHint(request),
                    true,
                    safeErrorMessage(ex)
            );
            return "TRIAGE_FAILED";
        }
    }

    private DispatchRequest findRequest(String requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));
    }

    private void publishTriaged(DispatchRequest request, TriageResponse triage) {
        eventOutboxService.recordRequestTriaged(
                request,
                triage,
                RULE_VERSION,
                requiresReview(triage),
                triggeredRules(triage)
        );
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

    private String fallbackSpecialtyHint(DispatchRequest request) {
        if (request.getSpecialtyHint() == null || request.getSpecialtyHint().isBlank()) {
            return "generaliste";
        }
        return request.getSpecialtyHint();
    }

    private String failureCode(RuntimeException ex) {
        String message = safeErrorMessage(ex).toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out")) {
            return "AI_TIMEOUT";
        }
        if (message.contains("http") || message.contains("unavailable") || message.contains("connection")) {
            return "AI_UNAVAILABLE";
        }
        if (message.contains("json") || message.contains("model output") || message.contains("schema")) {
            return "INVALID_MODEL_OUTPUT";
        }
        return "PIPELINE_ERROR";
    }

    private String safeErrorMessage(RuntimeException ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return ex.getMessage();
    }
}

package com.pulsaride.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pulsaride.dispatch.ai.AiTriageService;
import com.pulsaride.dispatch.api.TriageResponse;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestTriageServiceTests {
    private final DispatchRequestRepository requestRepository = mock(DispatchRequestRepository.class);
    private final AiTriageService aiTriageService = mock(AiTriageService.class);
    private final EventOutboxService eventOutboxService = mock(EventOutboxService.class);
    private final RequestTriageService service = new RequestTriageService(
            requestRepository,
            aiTriageService,
            eventOutboxService
    );

    @Test
    void triageAndPublishOutcomePublishesTriagedEvent() {
        DispatchRequest request = request("req_ok", "generaliste", 1);
        TriageResponse triage = new TriageResponse(
                List.of("chest_pain"),
                1,
                3,
                "adulte",
                "cardiologie",
                3,
                "mock",
                0.91,
                "Chest pain red flag detected.",
                "pulsaride-rules"
        );
        given(requestRepository.findById("req_ok")).willReturn(Optional.of(request));
        given(aiTriageService.triage(request.getPatientText())).willReturn(triage);

        String outcome = service.triageAndPublishOutcome("req_ok");

        assertThat(outcome).isEqualTo("TRIAGED");
        verify(eventOutboxService).recordRequestTriaged(
                eq(request),
                eq(triage),
                eq("v2402-r1"),
                eq(true),
                anyList()
        );
        verify(eventOutboxService, never()).recordTriageFailed(
                eq(request),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void triageAndPublishOutcomeRecordsFailureEventWhenProviderFails() {
        DispatchRequest request = request("req_failed", "", 2);
        given(requestRepository.findById("req_failed")).willReturn(Optional.of(request));
        given(aiTriageService.triage(request.getPatientText()))
                .willThrow(new IllegalStateException("External AI triage returned HTTP 503: unavailable"));

        String outcome = service.triageAndPublishOutcome("req_failed");

        assertThat(outcome).isEqualTo("TRIAGE_FAILED");
        verify(eventOutboxService).recordTriageFailed(
                request,
                "AI_UNAVAILABLE",
                2,
                "generaliste",
                true,
                "External AI triage returned HTTP 503: unavailable"
        );
        verify(eventOutboxService, never()).recordRequestTriaged(
                eq(request),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                anyList()
        );
    }

    private DispatchRequest request(String id, String specialtyHint, int urgencyScore) {
        DispatchRequest request = new DispatchRequest();
        request.setId(id);
        request.setPatientId("patient_" + id);
        request.setPatientText("douleur poitrine et essoufflement");
        request.setSpecialtyHint(specialtyHint);
        request.setUrgencyScore(urgencyScore);
        return request;
    }
}

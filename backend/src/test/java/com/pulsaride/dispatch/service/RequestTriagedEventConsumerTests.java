package com.pulsaride.dispatch.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestTriagedEventConsumerTests {
    private final DispatchService dispatchService = mock(DispatchService.class);
    private final DispatchRequestRepository requestRepository = mock(DispatchRequestRepository.class);
    private final ProcessedEventService processedEventService = mock(ProcessedEventService.class);
    private final RequestTriagedEventConsumer consumer = new RequestTriagedEventConsumer(
            dispatchService,
            requestRepository,
            new ObjectMapper(),
            processedEventService
    );

    @BeforeEach
    void setUp() {
        given(requestRepository.existsById(anyString())).willReturn(true);
        given(processedEventService.processOnce(anyString(), anyString(), anyString(), any()))
                .willAnswer(invocation -> {
                    ProcessedEventService.EventHandler handler = invocation.getArgument(3);
                    handler.handle();
                    return true;
                });
    }

    @Test
    void consumesRequestTriagedEventAndDispatchesRequest() {
        consumer.onMessage("""
                {
                  "eventId": "evt_1",
                  "eventType": "request.triaged.v1",
                  "aggregateId": "req_1",
                  "correlationId": "corr_1",
                  "occurredAt": "2026-08-23T12:00:00Z",
                  "producer": "ai-triage-service",
                  "schemaVersion": 1,
                  "payload": {
                    "requestId": "req_1",
                    "urgencyScore": 3,
                    "specialtyHint": "cardiologie",
                    "confidence": 0.82,
                    "modelVersion": "darija-health-nlp",
                    "ruleVersion": "v2402-r1",
                    "requiresReview": false
                  }
                }
                """);

        verify(dispatchService).applyTriageAndDispatch(
                "req_1",
                3,
                "cardiologie",
                "AI triage applied: model=darija-health-nlp, confidence=0.82, requiresReview=false"
        );
        verify(processedEventService).processOnce(eq("evt_1"), eq("request.triaged.v1"), eq("req_1"), any());
    }

    @Test
    void ignoresOtherEventTypes() {
        consumer.onMessage("""
                {
                  "eventType": "request.created.v1",
                  "payload": { "requestId": "req_1" }
                }
                """);

        verify(dispatchService, never()).applyTriageAndDispatch(
                anyString(),
                anyInt(),
                anyString(),
                anyString()
        );
        verify(processedEventService, never()).processOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    void skipsUnknownRequestsWithoutBlockingConsumer() {
        given(requestRepository.existsById("req_missing")).willReturn(false);

        assertDoesNotThrow(() -> consumer.onMessage("""
                {
                  "eventId": "evt_missing",
                  "eventType": "request.triaged.v1",
                  "aggregateId": "req_missing",
                  "payload": {
                    "requestId": "req_missing",
                    "urgencyScore": 3,
                    "specialtyHint": "cardiologie",
                    "confidence": 0.91,
                    "modelVersion": "simulator",
                    "requiresReview": false
                  }
                }
                """));
        verify(processedEventService).processOnce(
                eq("evt_missing"),
                eq("request.triaged.v1"),
                eq("req_missing"),
                any()
        );
        verify(dispatchService, never()).applyTriageAndDispatch(
                anyString(),
                anyInt(),
                anyString(),
                anyString()
        );
    }

    @Test
    void skipsDuplicateTriageEventsBeforeDispatchingAgain() {
        doReturn(false)
                .when(processedEventService)
                .processOnce(anyString(), anyString(), anyString(), any());

        consumer.onMessage("""
                {
                  "eventId": "evt_duplicate",
                  "eventType": "request.triaged.v1",
                  "aggregateId": "req_1",
                  "payload": {
                    "requestId": "req_1",
                    "urgencyScore": 3,
                    "specialtyHint": "cardiologie",
                    "confidence": 0.82,
                    "modelVersion": "darija-health-nlp",
                    "requiresReview": false
                  }
                }
                """);

        verify(dispatchService, never()).applyTriageAndDispatch(
                anyString(),
                anyInt(),
                anyString(),
                anyString()
        );
    }
}

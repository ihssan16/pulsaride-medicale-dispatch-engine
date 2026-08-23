package com.pulsaride.dispatch.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestCreatedEventConsumerTests {
    private final RequestTriageService requestTriageService = org.mockito.Mockito.mock(RequestTriageService.class);
    private final DispatchRequestRepository requestRepository = org.mockito.Mockito.mock(DispatchRequestRepository.class);
    private final ProcessedEventService processedEventService = org.mockito.Mockito.mock(ProcessedEventService.class);
    private final RequestCreatedEventConsumer consumer = new RequestCreatedEventConsumer(
            requestTriageService,
            requestRepository,
            new ObjectMapper(),
            processedEventService
    );

    @BeforeEach
    void setUp() {
        given(requestRepository.existsById(anyString())).willReturn(true);
        given(requestTriageService.triageAndPublishOutcome(anyString())).willReturn("TRIAGED");
        given(processedEventService.processOnce(anyString(), anyString(), anyString(), any()))
                .willAnswer(invocation -> {
                    ProcessedEventService.EventHandler handler = invocation.getArgument(3);
                    handler.handle();
                    return true;
                });
    }

    @Test
    void consumesRequestCreatedEventAndRunsAiTriage() {
        consumer.onMessage("""
                {
                  "eventId": "evt_created_1",
                  "eventType": "request.created.v1",
                  "aggregateId": "req_1",
                  "correlationId": "corr_1",
                  "occurredAt": "2026-08-23T12:00:00Z",
                  "producer": "demand-service",
                  "schemaVersion": 1,
                  "payload": {
                    "requestId": "req_1",
                    "patientId": "patient_1",
                    "freeText": "douleur poitrine",
                    "specialtyHint": "generaliste",
                    "initialUrgencyScore": 1,
                    "createdAt": "2026-08-23T12:00:00Z"
                  }
                }
                """);

        verify(requestTriageService).triageAndPublishOutcome("req_1");
        verify(processedEventService).processOnce(eq("evt_created_1"), eq("request.created.v1"), eq("req_1"), any());
    }

    @Test
    void ignoresOtherEventTypes() {
        consumer.onMessage("""
                {
                  "eventType": "request.triaged.v1",
                  "payload": { "requestId": "req_1" }
                }
                """);

        verify(requestTriageService, never()).triageAndPublishOutcome(anyString());
        verify(processedEventService, never()).processOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    void skipsUnknownRequestsWithoutBlockingConsumer() {
        given(requestRepository.existsById("req_missing")).willReturn(false);

        assertDoesNotThrow(() -> consumer.onMessage("""
                {
                  "eventId": "evt_missing",
                  "eventType": "request.created.v1",
                  "aggregateId": "req_missing",
                  "payload": {
                    "requestId": "req_missing",
                    "patientId": "patient_missing",
                    "freeText": "douleur",
                    "specialtyHint": "generaliste",
                    "initialUrgencyScore": 1,
                    "createdAt": "2026-08-23T12:00:00Z"
                  }
                }
                """));

        verify(processedEventService).processOnce(
                eq("evt_missing"),
                eq("request.created.v1"),
                eq("req_missing"),
                any()
        );
        verify(requestTriageService, never()).triageAndPublishOutcome(anyString());
    }

    @Test
    void skipsDuplicateCreatedEventsBeforeTriagingAgain() {
        doReturn(false)
                .when(processedEventService)
                .processOnce(anyString(), anyString(), anyString(), any());

        consumer.onMessage("""
                {
                  "eventId": "evt_duplicate",
                  "eventType": "request.created.v1",
                  "aggregateId": "req_1",
                  "payload": {
                    "requestId": "req_1",
                    "patientId": "patient_1",
                    "freeText": "douleur",
                    "specialtyHint": "generaliste",
                    "initialUrgencyScore": 1,
                    "createdAt": "2026-08-23T12:00:00Z"
                  }
                }
                """);

        verify(requestTriageService, never()).triageAndPublishOutcome(anyString());
    }
}

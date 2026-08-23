package com.pulsaride.dispatch.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

class RequestTriagedEventConsumerTests {
    private final DispatchService dispatchService = mock(DispatchService.class);
    private final RequestTriagedEventConsumer consumer = new RequestTriagedEventConsumer(
            dispatchService,
            new ObjectMapper()
    );

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
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void skipsUnknownRequestsWithoutBlockingConsumer() {
        doThrow(new EntityNotFoundException("Request not found: req_missing"))
                .when(dispatchService)
                .applyTriageAndDispatch(
                        org.mockito.ArgumentMatchers.eq("req_missing"),
                        org.mockito.ArgumentMatchers.eq(3),
                        org.mockito.ArgumentMatchers.eq("cardiologie"),
                        org.mockito.ArgumentMatchers.anyString()
                );

        assertDoesNotThrow(() -> consumer.onMessage("""
                {
                  "eventType": "request.triaged.v1",
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
    }
}

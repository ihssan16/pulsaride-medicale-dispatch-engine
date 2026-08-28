package com.pulsaride.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.OutboxEvent;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.AvailabilitySlotRepository;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.OutboxEventRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DispatchApiIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private StateTransitionRepository transitionRepository;

    @Autowired
    private DispatchRequestRepository requestRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProfessionalRepository professionalRepository;

    @Autowired
    private AvailabilitySlotRepository slotRepository;

    @MockBean
    private DispatchRedisService redisService;

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        transitionRepository.deleteAll();
        outboxEventRepository.deleteAll();
        requestRepository.deleteAll();
        slotRepository.deleteAll();
        professionalRepository.deleteAll();

        reset(redisService);
        given(redisService.acquireAssignmentLock(anyString())).willReturn(true);
        given(redisService.acquireSlotLock(anyString(), anyString())).willReturn(true);
    }

    @Test
    void requestCanBeDispatchedAcceptedClosedAndInspectedThroughHttp() throws Exception {
        createProfessional("api_pro_er", "urgence");
        String requestId = createRequest("api_patient_closed", "urgence");

        mockMvc.perform(post("/dispatch/{requestId}", requestId)
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.assignedProfessionalId").value("api_pro_er"))
                .andExpect(jsonPath("$.assignedSlotId").value("slot_api_pro_er"));

        mockMvc.perform(get("/requests/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.assignedProfessionalId").value("api_pro_er"))
                .andExpect(jsonPath("$.assignedProfessionalName").value("Dr. api_pro_er"))
                .andExpect(jsonPath("$.assignedSlotId").value("slot_api_pro_er"));

        mockMvc.perform(post("/dispatch/{requestId}/accept", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.ttrMs").isNumber());

        mockMvc.perform(post("/dispatch/{requestId}/close", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists());

        mockMvc.perform(get("/requests/{requestId}/assignments", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].outcome").value("CLOSED"))
                .andExpect(jsonPath("$[0].professionalId").value("api_pro_er"));

        mockMvc.perform(get("/requests/{requestId}/transitions", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].toStatus").value("RESERVED"))
                .andExpect(jsonPath("$[2].toStatus").value("PROPOSED"))
                .andExpect(jsonPath("$[3].toStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$[4].toStatus").value("CLOSED"));

        mockMvc.perform(get("/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1))
                .andExpect(jsonPath("$.closedRequests").value(1))
                .andExpect(jsonPath("$.totalAssignments").value(1))
                .andExpect(jsonPath("$.serviceRatePct").value(100.0))
                .andExpect(jsonPath("$.failureRatePct").value(0.0))
                .andExpect(jsonPath("$.avgTtfaMs").isNumber())
                .andExpect(jsonPath("$.p95TtfaMs").isNumber())
                .andExpect(jsonPath("$.avgTtrMs").isNumber())
                .andExpect(jsonPath("$.p95TtrMs").isNumber())
                .andExpect(jsonPath("$.avgDegradedReassignmentMs").doesNotExist())
                .andExpect(jsonPath("$.p95DegradedReassignmentMs").doesNotExist());
    }

    @Test
    void refusalAndTimeoutEndpointsRecycleRequestsAndRecordOutcomes() throws Exception {
        createProfessional("api_pro_refuse", "cardiologie");
        createProfessional("api_pro_timeout", "radiologie");
        String refusedRequestId = createRequest("api_patient_refused", "cardiologie");
        String timedOutRequestId = createRequest("api_patient_timeout", "radiologie");

        mockMvc.perform(post("/dispatch/{requestId}", refusedRequestId)
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        mockMvc.perform(post("/dispatch/{requestId}/refuse", refusedRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.assignedProfessionalId").doesNotExist())
                .andExpect(jsonPath("$.failureReason").value("Professional refused"));

        mockMvc.perform(post("/dispatch/{requestId}", timedOutRequestId)
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        mockMvc.perform(post("/dispatch/{requestId}/timeout", timedOutRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.assignedProfessionalId").doesNotExist())
                .andExpect(jsonPath("$.failureReason").value("Proposal timed out"));

        mockMvc.perform(get("/requests/{requestId}/assignments", refusedRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$[0].refusedAt").exists());

        mockMvc.perform(get("/requests/{requestId}/assignments", timedOutRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].outcome").value("TIMED_OUT"))
                .andExpect(jsonPath("$[0].timedOutAt").exists());

        mockMvc.perform(get("/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(2))
                .andExpect(jsonPath("$.pendingRequests").value(2))
                .andExpect(jsonPath("$.refusedAssignments").value(1))
                .andExpect(jsonPath("$.timedOutAssignments").value(1))
                .andExpect(jsonPath("$.refusalRatePct").value(50.0))
                .andExpect(jsonPath("$.timeoutRatePct").value(50.0));
    }

    @Test
    void dispatchNextEndpointUsesUrgencyPriority() throws Exception {
        createProfessional("api_pro_priority", "cardiologie");
        createRequest("api_patient_low_priority", "cardiologie", 0);
        String highPriorityRequestId = createRequest("api_patient_high_priority", "cardiologie", 3);

        mockMvc.perform(post("/dispatch/next")
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(highPriorityRequestId))
                .andExpect(jsonPath("$.patientId").value("api_patient_high_priority"))
                .andExpect(jsonPath("$.urgencyScore").value(3))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.assignedProfessionalId").value("api_pro_priority"));
    }

    @Test
    void apiV2RoutesExposeGatewayCompatibleWorkflow() throws Exception {
        mockMvc.perform(get("/api/v2/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("pulsaride-dispatch-engine"));

        mockMvc.perform(post("/api/v2/professionals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProfessionalRequest(
                                "api_v2_pro_cardio",
                                "Dr. API V2 Cardio",
                                "cardiologie",
                                9,
                                "Cardiologie V2",
                                6,
                                ProfessionalStatus.AVAILABLE
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("api_v2_pro_cardio"));

        String response = mockMvc.perform(post("/api/v2/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDispatchRequest(
                                "api_v2_patient",
                                "Douleur thoracique",
                                "cardiologie",
                                3
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requestId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(post("/api/v2/dispatch/{requestId}", requestId)
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.assignedProfessionalId").value("api_v2_pro_cardio"));

        mockMvc.perform(get("/api/v2/requests/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedProfessionalName").value("Dr. API V2 Cardio"));

        mockMvc.perform(get("/api/v2/availability/specialties/cardiologie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedSlots").value(1));

        mockMvc.perform(get("/api/v2/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1))
                .andExpect(jsonPath("$.proposedRequests").value(1));

        mockMvc.perform(get("/api/v2/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.totalRequests").value(1))
                .andExpect(jsonPath("$.availability.reservedSlots").value(1))
                .andExpect(jsonPath("$.events.totalEvents").value(4))
                .andExpect(jsonPath("$.events.publishedEvents").value(0))
                .andExpect(jsonPath("$.events.unpublishedEvents").value(4))
                .andExpect(jsonPath("$.events.eventTypes[0].eventType").value("availability.changed.v1"))
                .andExpect(jsonPath("$.events.eventTypes[0].total").value(2))
                .andExpect(jsonPath("$.events.eventTypes[1].eventType").value("dispatch.proposed.v1"))
                .andExpect(jsonPath("$.events.eventTypes[2].eventType").value("request.created.v1"))
                .andExpect(jsonPath("$.professionalLoads", hasSize(1)))
                .andExpect(jsonPath("$.professionalLoads[0].id").value("api_v2_pro_cardio"));
    }

    @Test
    void apiV2RequestTriagePublishesRequestTriagedEvent() throws Exception {
        String response = mockMvc.perform(post("/api/v2/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDispatchRequest(
                                "api_v2_patient_triage",
                                "Douleur thoracique avec essoufflement",
                                "generaliste",
                                1
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requestId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(post("/api/v2/requests/{requestId}/triage", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgencyScore").value(3))
                .andExpect(jsonPath("$.specialtyHint").value("cardiologie"))
                .andExpect(jsonPath("$.sourceModel").value("pulsaride-rules"));

        OutboxEvent triagedEvent = outboxEventRepository.findByAggregateIdOrderByOccurredAtAsc(requestId)
                .stream()
                .filter(event -> event.getEventType().equals("request.triaged.v1"))
                .findFirst()
                .orElseThrow();

        assertThat(triagedEvent.getProducer()).isEqualTo("ai-triage-service");
        assertThat(triagedEvent.isPublished()).isFalse();
        assertThat(triagedEvent.getPayloadJson()).contains(
                "\"requestId\":\"" + requestId + "\"",
                "\"urgencyScore\":3",
                "\"specialtyHint\":\"cardiologie\"",
                "\"confidence\":1.0",
                "\"modelVersion\":\"pulsaride-rules\"",
                "\"ruleVersion\":\"v2402-r1\"",
                "\"requiresReview\":true",
                "\"triggeredRules\":[\"LOCAL_RULES\"]"
        );
    }

    @Test
    void apiV2RequestCreationAutomaticallyTriagesWhenOnlyPatientTextIsProvided() throws Exception {
        String response = mockMvc.perform(post("/api/v2/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patientId": "api_v2_patient_auto_triage",
                                  "patientText": "wldi chrab dawa bzzaf w kayt9aya"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.specialtyHint").value("urgence"))
                .andExpect(jsonPath("$.urgencyScore").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requestId = objectMapper.readTree(response).get("id").asText();

        assertThat(outboxEventRepository.findByAggregateIdOrderByOccurredAtAsc(requestId))
                .hasSize(2)
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo("request.triaged.v1");
                    assertThat(event.getPayloadJson()).contains(
                            "\"specialtyHint\":\"urgence\"",
                            "\"urgencyScore\":3"
                    );
                });
    }

    @Test
    void availabilityEndpointsReflectProfessionalLifecycle() throws Exception {
        createProfessional("api_pro_cardio_1", "cardiologie");
        createProfessional("api_pro_cardio_2", "cardiologie");
        createProfessional("api_pro_er", "urgence");
        String requestId = createRequest("api_patient_availability", "cardiologie");

        mockMvc.perform(get("/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(3))
                .andExpect(jsonPath("$.availableSlots").value(3))
                .andExpect(jsonPath("$.availableCapacity").value(18))
                .andExpect(jsonPath("$.specialties[0].specialtyTag").value("cardiologie"))
                .andExpect(jsonPath("$.specialties[0].availableSlots").value(2));

        mockMvc.perform(put("/professionals/{id}/status", "api_pro_er")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "OFFLINE" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));

        mockMvc.perform(get("/api/availability/specialties/urgence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(1))
                .andExpect(jsonPath("$.offlineSlots").value(1))
                .andExpect(jsonPath("$.availableCapacity").value(0));
        assertThat(outboxEventRepository.findByAggregateIdOrderByOccurredAtAsc("api_pro_er"))
                .extracting("payloadJson")
                .anySatisfy(payload -> assertThat((String) payload).contains(
                        "\"previousStatus\":\"AVAILABLE\"",
                        "\"newStatus\":\"OFFLINE\"",
                        "\"reason\":\"professional_status_updated\""
                ));

        mockMvc.perform(post("/dispatch/{requestId}", requestId)
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"));

        mockMvc.perform(get("/availability/specialties/cardiologie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSlots").value(2))
                .andExpect(jsonPath("$.availableSlots").value(1))
                .andExpect(jsonPath("$.reservedSlots").value(1))
                .andExpect(jsonPath("$.availableSlotIds", hasSize(1)))
                .andExpect(jsonPath("$.availableProfessionalIds", hasSize(1)));

        mockMvc.perform(post("/dispatch/{requestId}/accept", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/availability/specialties/cardiologie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSlots").value(1))
                .andExpect(jsonPath("$.busySlots").value(1));

        mockMvc.perform(post("/dispatch/{requestId}/close", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/availability/specialties/cardiologie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSlots").value(2))
                .andExpect(jsonPath("$.busySlots").value(0));
    }

    @Test
    void invalidRequestPayloadReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patientId": "",
                                  "patientText": "",
                                  "specialtyHint": "cardiologie",
                                  "urgencyScore": 8
                                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.message").exists());
    }

    private void createProfessional(String id, String specialtyTag) throws Exception {
        mockMvc.perform(post("/professionals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProfessionalRequest(
                                id,
                                "Dr. " + id,
                                specialtyTag,
                                8,
                                "Specialiste " + specialtyTag,
                                6,
                                ProfessionalStatus.AVAILABLE
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    private String createRequest(String patientId, String specialtyHint) throws Exception {
        return createRequest(patientId, specialtyHint, 2);
    }

    private String createRequest(String patientId, String specialtyHint, int urgencyScore) throws Exception {
        String response = mockMvc.perform(post("/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDispatchRequest(
                                patientId,
                                "Patient avec symptomes " + specialtyHint,
                                specialtyHint,
                                urgencyScore
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asText();
    }
}

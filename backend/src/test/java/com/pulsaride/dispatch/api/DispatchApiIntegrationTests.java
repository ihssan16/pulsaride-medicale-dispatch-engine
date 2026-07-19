package com.pulsaride.dispatch.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
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
    private ProfessionalRepository professionalRepository;

    @MockBean
    private DispatchRedisService redisService;

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        transitionRepository.deleteAll();
        requestRepository.deleteAll();
        professionalRepository.deleteAll();

        reset(redisService);
        given(redisService.acquireAssignmentLock(anyString())).willReturn(true);
    }

    @Test
    void requestCanBeDispatchedAcceptedClosedAndInspectedThroughHttp() throws Exception {
        createProfessional("api_pro_er", "urgence");
        String requestId = createRequest("api_patient_closed", "urgence");

        mockMvc.perform(post("/dispatch/{requestId}", requestId)
                        .queryParam("strategy", "S2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.assignedProfessionalId").value("api_pro_er"));

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
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].toStatus").value("PROPOSED"))
                .andExpect(jsonPath("$[2].toStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$[3].toStatus").value("CLOSED"));

        mockMvc.perform(get("/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(1))
                .andExpect(jsonPath("$.closedRequests").value(1))
                .andExpect(jsonPath("$.totalAssignments").value(1))
                .andExpect(jsonPath("$.serviceRatePct").value(100.0));
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

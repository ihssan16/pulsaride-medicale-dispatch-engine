package com.pulsaride.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import com.pulsaride.dispatch.api.CreateDispatchRequest;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.matching.DispatchStrategy;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class DispatchServiceTests {
    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private ProfessionalRepository professionalRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private StateTransitionRepository transitionRepository;

    @Autowired
    private MetricsService metricsService;

    @MockBean
    private DispatchRedisService redisService;

    @BeforeEach
    void setUp() {
        reset(redisService);
        given(redisService.acquireAssignmentLock(anyString())).willReturn(true);
    }

    @Test
    void assignsAvailableProfessionalBySpecialtyAndRecordsAuditTrail() {
        professionalRepository.save(professional("pro_cardio", "cardiologie", ProfessionalStatus.AVAILABLE));

        var result = dispatchService.createAndDispatch(new CreateDispatchRequest(
                "patient_test",
                "J'ai des palpitations.",
                "cardiologie",
                3
        ));

        assertThat(result.getStatus()).isEqualTo(RequestStatus.PROPOSED);
        assertThat(result.getAssignedProfessional().getId()).isEqualTo("pro_cardio");
        assertThat(result.getTtfaMs()).isNotNegative();

        var assignments = assignmentRepository.findByRequestIdOrderByProposedAtDesc(result.getId());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().getOutcome()).isEqualTo(AssignmentOutcome.PROPOSED);
        assertThat(assignments.getFirst().getStrategy()).isEqualTo(DispatchStrategy.S3);

        var transitions = transitionRepository.findByRequestIdOrderByOccurredAtAsc(result.getId());
        assertThat(transitions).extracting("toStatus")
                .containsExactly(RequestStatus.PENDING, RequestStatus.PROPOSED);
    }

    @Test
    void acceptThenCloseCompletesRequestAndReleasesProfessional() {
        professionalRepository.save(professional("pro_er", "urgence", ProfessionalStatus.AVAILABLE));
        var request = dispatchService.createAndDispatch(command("patient_accept", "urgence"));

        var accepted = dispatchService.accept(request.getId());
        assertThat(accepted.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
        assertThat(accepted.getTtrMs()).isNotNegative();
        assertThat(professionalRepository.findById("pro_er").orElseThrow().getStatus())
                .isEqualTo(ProfessionalStatus.BUSY);
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(request.getId()))
                .singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.getOutcome()).isEqualTo(AssignmentOutcome.ACCEPTED);
                    assertThat(assignment.getAcceptedAt()).isNotNull();
                });

        var closed = dispatchService.close(request.getId());
        assertThat(closed.getStatus()).isEqualTo(RequestStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(professionalRepository.findById("pro_er").orElseThrow().getStatus())
                .isEqualTo(ProfessionalStatus.AVAILABLE);
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(request.getId()))
                .singleElement()
                .satisfies(assignment -> assertThat(assignment.getOutcome()).isEqualTo(AssignmentOutcome.CLOSED));

        var transitions = transitionRepository.findByRequestIdOrderByOccurredAtAsc(request.getId());
        assertThat(transitions).extracting("toStatus")
                .containsExactly(
                        RequestStatus.PENDING,
                        RequestStatus.PROPOSED,
                        RequestStatus.ACCEPTED,
                        RequestStatus.CLOSED
                );
    }

    @Test
    void refusalReturnsRequestToQueueAndNextDispatchRetriesAnotherProfessional() {
        professionalRepository.save(professional("pro_first", "cardiologie", ProfessionalStatus.AVAILABLE));
        professionalRepository.save(professional("pro_second", "cardiologie", ProfessionalStatus.AVAILABLE));
        var request = dispatchService.createAndDispatch(command("patient_refuse", "cardiologie"));
        String firstProfessionalId = request.getAssignedProfessional().getId();

        var refused = dispatchService.refuse(request.getId());
        assertThat(refused.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(refused.getAssignedProfessional()).isNull();
        assertThat(refused.getProposedAt()).isNull();
        assertThat(refused.getFailureReason()).isEqualTo("Professional refused");
        assertThat(professionalRepository.findById(firstProfessionalId).orElseThrow().getStatus())
                .isEqualTo(ProfessionalStatus.BREAK);

        var historyAfterRefusal = assignmentRepository.findByRequestIdOrderByProposedAtDesc(request.getId());
        assertThat(historyAfterRefusal).singleElement().satisfies(assignment -> {
            assertThat(assignment.getOutcome()).isEqualTo(AssignmentOutcome.REFUSED);
            assertThat(assignment.getRefusedAt()).isNotNull();
        });

        var retried = dispatchService.dispatch(request.getId(), DispatchStrategy.S2);
        assertThat(retried.getStatus()).isEqualTo(RequestStatus.PROPOSED);
        assertThat(retried.getAssignedProfessional().getId()).isNotEqualTo(firstProfessionalId);
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(request.getId()))
                .extracting("outcome")
                .containsExactly(AssignmentOutcome.PROPOSED, AssignmentOutcome.REFUSED);
    }

    @Test
    void timeoutReturnsRequestToQueueAndKeepsTimedOutAssignment() {
        professionalRepository.save(professional("pro_timeout", "radiologie", ProfessionalStatus.AVAILABLE));
        var request = dispatchService.createAndDispatch(command("patient_timeout", "radiologie"));

        var timedOut = dispatchService.timeout(request.getId());

        assertThat(timedOut.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(timedOut.getAssignedProfessional()).isNull();
        assertThat(timedOut.getProposedAt()).isNull();
        assertThat(timedOut.getFailureReason()).isEqualTo("Proposal timed out");
        assertThat(professionalRepository.findById("pro_timeout").orElseThrow().getStatus())
                .isEqualTo(ProfessionalStatus.BREAK);
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(request.getId()))
                .singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.getOutcome()).isEqualTo(AssignmentOutcome.TIMED_OUT);
                    assertThat(assignment.getTimedOutAt()).isNotNull();
                });
        assertThat(transitionRepository.findByRequestIdOrderByOccurredAtAsc(request.getId()))
                .extracting("toStatus")
                .containsExactly(
                        RequestStatus.PENDING,
                        RequestStatus.PROPOSED,
                        RequestStatus.FAILED,
                        RequestStatus.PENDING
                );
    }

    @Test
    void noAvailableProfessionalMarksRequestFailed() {
        var request = dispatchService.create(command("patient_failed", "pediatrie"));

        var failed = dispatchService.dispatch(request.getId(), DispatchStrategy.S2);

        assertThat(failed.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("No professional available");
        assertThat(failed.getClosedAt()).isNotNull();
        assertThat(failed.getTtrMs()).isNotNegative();
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(request.getId())).isEmpty();
        assertThat(transitionRepository.findByRequestIdOrderByOccurredAtAsc(request.getId()))
                .extracting("toStatus")
                .containsExactly(RequestStatus.PENDING, RequestStatus.FAILED);
    }

    @Test
    void metricsSummarizeServiceRefusalTimeoutAndFairness() {
        professionalRepository.saveAll(List.of(
                professional("pro_closed", "urgence", ProfessionalStatus.AVAILABLE),
                professional("pro_refused", "cardiologie", ProfessionalStatus.AVAILABLE),
                professional("pro_timed", "radiologie", ProfessionalStatus.AVAILABLE)
        ));

        var closed = dispatchService.createAndDispatch(command("patient_closed", "urgence"));
        dispatchService.accept(closed.getId());
        dispatchService.close(closed.getId());

        var refused = dispatchService.createAndDispatch(command("patient_refused_metrics", "cardiologie"));
        dispatchService.refuse(refused.getId());

        var timedOut = dispatchService.createAndDispatch(command("patient_timeout_metrics", "radiologie"));
        dispatchService.timeout(timedOut.getId());

        var summary = metricsService.summary();

        assertThat(summary.totalRequests()).isEqualTo(3);
        assertThat(summary.pendingRequests()).isEqualTo(2);
        assertThat(summary.closedRequests()).isEqualTo(1);
        assertThat(summary.failedRequests()).isZero();
        assertThat(summary.totalAssignments()).isEqualTo(3);
        assertThat(summary.refusedAssignments()).isEqualTo(1);
        assertThat(summary.timedOutAssignments()).isEqualTo(1);
        assertThat(summary.serviceRatePct()).isEqualTo(33.33);
        assertThat(summary.refusalRatePct()).isEqualTo(33.33);
        assertThat(summary.timeoutRatePct()).isEqualTo(33.33);
        assertThat(summary.avgTtfaMs()).isNotNull();
        assertThat(summary.avgTtrMs()).isNotNull();
        assertThat(summary.giniFairness()).isGreaterThan(0.0);
    }

    private CreateDispatchRequest command(String patientId, String specialtyHint) {
        return new CreateDispatchRequest(
                patientId,
                "Patient avec symptomes compatibles " + specialtyHint,
                specialtyHint,
                2
        );
    }

    private Professional professional(String id, String specialtyTag, ProfessionalStatus status) {
        Professional professional = new Professional();
        professional.setId(id);
        professional.setName("Dr. " + id);
        professional.setSpecialtyTag(specialtyTag);
        professional.setExperienceYears(10);
        professional.setProfileText("Specialiste " + specialtyTag);
        professional.setQuotaMaxPerHour(6);
        professional.setStatus(status);
        return professional;
    }
}

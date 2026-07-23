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
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import java.time.OffsetDateTime;
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
    private DispatchRequestRepository requestRepository;

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
        given(redisService.acquireSlotLock(anyString(), anyString())).willReturn(true);
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
        assertThat(result.getAssignedSlot().getId()).isEqualTo("slot_pro_cardio");
        assertThat(result.getTtfaMs()).isNotNegative();

        var assignments = assignmentRepository.findByRequestIdOrderByProposedAtDesc(result.getId());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().getOutcome()).isEqualTo(AssignmentOutcome.PROPOSED);
        assertThat(assignments.getFirst().getStrategy()).isEqualTo(DispatchStrategy.S3);

        var transitions = transitionRepository.findByRequestIdOrderByOccurredAtAsc(result.getId());
        assertThat(transitions).extracting("toStatus")
                .containsExactly(RequestStatus.PENDING, RequestStatus.RESERVED, RequestStatus.PROPOSED);
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
                        RequestStatus.RESERVED,
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
                        RequestStatus.RESERVED,
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
    void dispatchNextChoosesHighestUrgencyBeforeOlderLowPriorityRequests() {
        professionalRepository.save(professional("pro_priority", "cardiologie", ProfessionalStatus.AVAILABLE));
        var lowPriority = dispatchService.create(command("patient_low_priority", "cardiologie", 0));
        var highPriority = dispatchService.create(command("patient_high_priority", "cardiologie", 3));

        var dispatched = dispatchService.dispatchNext(DispatchStrategy.S2);

        assertThat(dispatched.getId()).isEqualTo(highPriority.getId());
        assertThat(dispatched.getStatus()).isEqualTo(RequestStatus.PROPOSED);
        assertThat(dispatched.getAssignedProfessional().getId()).isEqualTo("pro_priority");
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(dispatched.getId()))
                .singleElement()
                .satisfies(assignment -> assertThat(assignment.getOutcome()).isEqualTo(AssignmentOutcome.PROPOSED));
        assertThat(assignmentRepository.findByRequestIdOrderByProposedAtDesc(lowPriority.getId())).isEmpty();
    }

    @Test
    void s1RotatesAvailableProfessionalsRoundRobin() {
        professionalRepository.save(professional("pro_alpha", "cardiologie", ProfessionalStatus.AVAILABLE));
        professionalRepository.save(professional("pro_beta", "cardiologie", ProfessionalStatus.AVAILABLE));
        professionalRepository.save(professional("pro_gamma", "cardiologie", ProfessionalStatus.AVAILABLE));

        var first = dispatchAndClose("patient_round_robin_1");
        var second = dispatchAndClose("patient_round_robin_2");
        var third = dispatchAndClose("patient_round_robin_3");

        assertThat(List.of(
                first.getAssignedProfessional().getId(),
                second.getAssignedProfessional().getId(),
                third.getAssignedProfessional().getId()
        )).containsExactly("pro_alpha", "pro_beta", "pro_gamma");
        assertThat(assignmentRepository.findAll().stream()
                .filter(assignment -> assignment.getStrategy() == DispatchStrategy.S1)
                .map(assignment -> assignment.getProfessional().getId())
                .toList()).containsExactly("pro_alpha", "pro_beta", "pro_gamma");
    }

    @Test
    void metricsSummarizeLatencyReassignmentRatesAndFairness() {
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

        professionalRepository.save(professional(
                "pro_refused_retry",
                "cardiologie",
                ProfessionalStatus.AVAILABLE
        ));
        dispatchService.dispatch(refused.getId(), DispatchStrategy.S2);

        var refusalHistory = assignmentRepository.findByRequestIdOrderByProposedAtDesc(refused.getId());
        var retriedAssignment = refusalHistory.get(0);
        var refusedAssignment = refusalHistory.get(1);
        OffsetDateTime refusedAt = OffsetDateTime.now().minusSeconds(2);
        refusedAssignment.setProposedAt(refusedAt.minusSeconds(1));
        refusedAssignment.setRefusedAt(refusedAt);
        retriedAssignment.setProposedAt(refusedAt.plusNanos(750_000_000));
        assignmentRepository.saveAll(refusalHistory);

        var timedOut = dispatchService.createAndDispatch(command("patient_timeout_metrics", "radiologie"));
        dispatchService.timeout(timedOut.getId());

        closed.setTtfaMs(100L);
        closed.setTtrMs(1_000L);
        refused.setTtfaMs(200L);
        refused.setTtrMs(2_000L);
        timedOut.setTtfaMs(300L);
        timedOut.setTtrMs(3_000L);
        requestRepository.saveAll(List.of(closed, refused, timedOut));

        var summary = metricsService.summary();

        assertThat(summary.totalRequests()).isEqualTo(3);
        assertThat(summary.pendingRequests()).isEqualTo(1);
        assertThat(summary.proposedRequests()).isEqualTo(1);
        assertThat(summary.closedRequests()).isEqualTo(1);
        assertThat(summary.failedRequests()).isZero();
        assertThat(summary.totalAssignments()).isEqualTo(4);
        assertThat(summary.refusedAssignments()).isEqualTo(1);
        assertThat(summary.timedOutAssignments()).isEqualTo(1);
        assertThat(summary.serviceRatePct()).isEqualTo(33.33);
        assertThat(summary.refusalRatePct()).isEqualTo(25.0);
        assertThat(summary.timeoutRatePct()).isEqualTo(25.0);
        assertThat(summary.failureRatePct()).isZero();
        assertThat(summary.avgTtfaMs()).isEqualTo(200.0);
        assertThat(summary.p95TtfaMs()).isEqualTo(300.0);
        assertThat(summary.avgTtrMs()).isEqualTo(2_000.0);
        assertThat(summary.p95TtrMs()).isEqualTo(3_000.0);
        assertThat(summary.avgDegradedReassignmentMs()).isEqualTo(750.0);
        assertThat(summary.p95DegradedReassignmentMs()).isEqualTo(750.0);
        assertThat(summary.giniFairness()).isGreaterThan(0.0);
    }

    private CreateDispatchRequest command(String patientId, String specialtyHint) {
        return command(patientId, specialtyHint, 2);
    }

    private CreateDispatchRequest command(String patientId, String specialtyHint, int urgencyScore) {
        return new CreateDispatchRequest(
                patientId,
                "Patient avec symptomes compatibles " + specialtyHint,
                specialtyHint,
                urgencyScore
        );
    }

    private com.pulsaride.dispatch.domain.DispatchRequest dispatchAndClose(String patientId) {
        var request = dispatchService.create(command(patientId, "cardiologie"));
        var proposed = dispatchService.dispatch(request.getId(), DispatchStrategy.S1);
        dispatchService.accept(proposed.getId());
        dispatchService.close(proposed.getId());
        return proposed;
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

package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.CreateDispatchRequest;
import com.pulsaride.dispatch.domain.Assignment;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.domain.StateTransition;
import com.pulsaride.dispatch.matching.DispatchStrategy;
import com.pulsaride.dispatch.matching.MatchingService;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchService {
    private final DispatchRequestRepository requestRepository;
    private final ProfessionalRepository professionalRepository;
    private final AssignmentRepository assignmentRepository;
    private final StateTransitionRepository transitionRepository;
    private final MatchingService matchingService;
    private final DispatchRedisService redisService;
    private final Duration proposalTimeout;

    public DispatchService(
            DispatchRequestRepository requestRepository,
            ProfessionalRepository professionalRepository,
            AssignmentRepository assignmentRepository,
            StateTransitionRepository transitionRepository,
            MatchingService matchingService,
            DispatchRedisService redisService,
            @Value("${pulsaride.dispatch.proposal-timeout-seconds:30}") long proposalTimeoutSeconds
    ) {
        this.requestRepository = requestRepository;
        this.professionalRepository = professionalRepository;
        this.assignmentRepository = assignmentRepository;
        this.transitionRepository = transitionRepository;
        this.matchingService = matchingService;
        this.redisService = redisService;
        this.proposalTimeout = Duration.ofSeconds(proposalTimeoutSeconds);
    }

    @Transactional
    public DispatchRequest create(CreateDispatchRequest command) {
        DispatchRequest request = new DispatchRequest();
        request.setId(UUID.randomUUID().toString());
        request.setPatientId(command.patientId());
        request.setPatientText(command.patientText());
        request.setSpecialtyHint(command.specialtyHint());
        request.setUrgencyScore(command.urgencyScore());
        request.setCreatedAt(OffsetDateTime.now());
        request.setStatus(RequestStatus.PENDING);
        DispatchRequest saved = requestRepository.save(request);
        recordTransition(saved, null, RequestStatus.PENDING, "Request created");
        redisService.enqueue(saved);
        return saved;
    }

    @Transactional
    public DispatchRequest createAndDispatch(CreateDispatchRequest command) {
        return dispatch(create(command).getId(), DispatchStrategy.S3);
    }

    @Transactional
    public DispatchRequest dispatch(String requestId) {
        return dispatch(requestId, DispatchStrategy.S3);
    }

    @Transactional
    public DispatchRequest dispatchNext(DispatchStrategy strategy) {
        DispatchRequest next = requestRepository.findFirstByStatusOrderByUrgencyScoreDescCreatedAtAsc(RequestStatus.PENDING)
                .orElseThrow(() -> new EntityNotFoundException("No pending request available"));
        return dispatch(next.getId(), strategy);
    }

    @Transactional
    public DispatchRequest dispatch(String requestId, DispatchStrategy strategy) {
        DispatchRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));

        if (request.getStatus() != RequestStatus.PENDING) {
            return request;
        }

        if (!redisService.acquireAssignmentLock(requestId)) {
            return request;
        }

        Professional selected = matchingService.select(request, strategy).orElse(null);
        if (selected == null) {
            transition(request, RequestStatus.FAILED, "No professional available");
            request.setFailureReason("No professional available");
            request.setClosedAt(OffsetDateTime.now());
            request.setTtrMs(Duration.between(request.getCreatedAt(), request.getClosedAt()).toMillis());
            redisService.removeFromQueue(request.getId());
            return request;
        }

        OffsetDateTime proposedAt = OffsetDateTime.now();
        transition(request, RequestStatus.PROPOSED, "Proposed to " + selected.getId() + " using " + strategy);
        request.setAssignedProfessional(selected);
        request.setProposedAt(proposedAt);
        if (request.getTtfaMs() == null) {
            request.setTtfaMs(Duration.between(request.getCreatedAt(), proposedAt).toMillis());
        }

        selected.setStatus(ProfessionalStatus.PROPOSED);
        professionalRepository.save(selected);
        assignmentRepository.save(assignment(request, selected, strategy, proposedAt));
        redisService.syncProfessional(selected);
        redisService.removeFromQueue(requestId);
        return request;
    }

    @Transactional
    public DispatchRequest accept(String requestId) {
        DispatchRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));
        Professional pro = request.getAssignedProfessional();
        if (pro == null) {
            throw new IllegalStateException("Request has no assigned professional");
        }

        OffsetDateTime acceptedAt = OffsetDateTime.now();
        transition(request, RequestStatus.ACCEPTED, "Professional accepted");
        request.setAcceptedAt(acceptedAt);
        request.setTtrMs(Duration.between(request.getCreatedAt(), acceptedAt).toMillis());
        pro.setStatus(ProfessionalStatus.BUSY);
        pro.setConsultationsToday(pro.getConsultationsToday() + 1);
        pro.setLoad((double) pro.getConsultationsToday() / pro.getQuotaMaxPerHour());
        professionalRepository.save(pro);
        assignmentRepository.findFirstByRequestIdAndOutcomeOrderByProposedAtDesc(
                requestId,
                AssignmentOutcome.PROPOSED
        ).ifPresent(assignment -> {
            assignment.setOutcome(AssignmentOutcome.ACCEPTED);
            assignment.setAcceptedAt(acceptedAt);
            assignmentRepository.save(assignment);
        });
        redisService.syncProfessional(pro);
        return request;
    }

    @Transactional
    public DispatchRequest close(String requestId) {
        DispatchRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));
        transition(request, RequestStatus.CLOSED, "Request closed");
        request.setClosedAt(OffsetDateTime.now());
        Professional pro = request.getAssignedProfessional();
        if (pro != null) {
            pro.setStatus(ProfessionalStatus.AVAILABLE);
            professionalRepository.save(pro);
            redisService.syncProfessional(pro);
        }
        assignmentRepository.findFirstByRequestIdAndOutcomeOrderByProposedAtDesc(
                requestId,
                AssignmentOutcome.ACCEPTED
        ).ifPresent(assignment -> {
            assignment.setOutcome(AssignmentOutcome.CLOSED);
            assignmentRepository.save(assignment);
        });
        return request;
    }

    @Transactional
    public DispatchRequest refuse(String requestId) {
        DispatchRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));
        return recycleProposal(request, AssignmentOutcome.REFUSED, "Professional refused");
    }

    @Transactional
    public DispatchRequest timeout(String requestId) {
        DispatchRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + requestId));
        return recycleProposal(request, AssignmentOutcome.TIMED_OUT, "Proposal timed out");
    }

    @Scheduled(fixedDelayString = "${pulsaride.dispatch.timeout-scan-ms:5000}")
    @Transactional
    public void timeoutExpiredProposals() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(proposalTimeout);
        List<DispatchRequest> expired = requestRepository.findByStatusAndProposedAtBefore(
                RequestStatus.PROPOSED,
                cutoff
        );
        expired.forEach(request -> recycleProposal(request, AssignmentOutcome.TIMED_OUT, "Proposal timed out"));
    }

    private DispatchRequest recycleProposal(
            DispatchRequest request,
            AssignmentOutcome outcome,
            String reason
    ) {
        if (request.getStatus() != RequestStatus.PROPOSED) {
            return request;
        }
        Professional pro = request.getAssignedProfessional();
        OffsetDateTime now = OffsetDateTime.now();

        transition(request, outcome == AssignmentOutcome.REFUSED ? RequestStatus.REFUSED : RequestStatus.FAILED, reason);
        request.setFailureReason(reason);

        if (pro != null) {
            pro.setStatus(ProfessionalStatus.BREAK);
            professionalRepository.save(pro);
            redisService.syncProfessional(pro);
        }

        assignmentRepository.findFirstByRequestIdAndOutcomeOrderByProposedAtDesc(
                request.getId(),
                AssignmentOutcome.PROPOSED
        ).ifPresent(assignment -> {
            assignment.setOutcome(outcome);
            if (outcome == AssignmentOutcome.REFUSED) {
                assignment.setRefusedAt(now);
            } else {
                assignment.setTimedOutAt(now);
            }
            assignmentRepository.save(assignment);
        });

        request.setAssignedProfessional(null);
        request.setProposedAt(null);
        transition(request, RequestStatus.PENDING, reason + "; returned to queue");
        redisService.enqueue(request);
        return request;
    }

    private Assignment assignment(
            DispatchRequest request,
            Professional professional,
            DispatchStrategy strategy,
            OffsetDateTime proposedAt
    ) {
        Assignment assignment = new Assignment();
        assignment.setRequest(request);
        assignment.setProfessional(professional);
        assignment.setStrategy(strategy);
        assignment.setProposedAt(proposedAt);
        assignment.setOutcome(AssignmentOutcome.PROPOSED);
        return assignment;
    }

    private void transition(DispatchRequest request, RequestStatus toStatus, String reason) {
        RequestStatus fromStatus = request.getStatus();
        request.setStatus(toStatus);
        recordTransition(request, fromStatus, toStatus, reason);
    }

    private void recordTransition(
            DispatchRequest request,
            RequestStatus fromStatus,
            RequestStatus toStatus,
            String reason
    ) {
        StateTransition transition = new StateTransition();
        transition.setRequest(request);
        transition.setFromStatus(fromStatus);
        transition.setToStatus(toStatus);
        transition.setReason(reason);
        transition.setOccurredAt(OffsetDateTime.now());
        transitionRepository.save(transition);
    }
}

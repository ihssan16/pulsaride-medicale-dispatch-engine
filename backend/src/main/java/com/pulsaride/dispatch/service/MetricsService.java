package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.DispatchMetricsResponse;
import com.pulsaride.dispatch.domain.Assignment;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricsService {
    private final DispatchRequestRepository requestRepository;
    private final AssignmentRepository assignmentRepository;
    private final ProfessionalRepository professionalRepository;

    public MetricsService(
            DispatchRequestRepository requestRepository,
            AssignmentRepository assignmentRepository,
            ProfessionalRepository professionalRepository
    ) {
        this.requestRepository = requestRepository;
        this.assignmentRepository = assignmentRepository;
        this.professionalRepository = professionalRepository;
    }

    @Transactional(readOnly = true)
    public DispatchMetricsResponse summary() {
        List<DispatchRequest> requests = requestRepository.findAll();
        List<Professional> professionals = professionalRepository.findAll();
        List<Assignment> assignments = assignmentRepository.findAllForMetrics();
        List<Long> ttfaValues = requests.stream()
                .map(DispatchRequest::getTtfaMs)
                .filter(v -> v != null)
                .toList();
        List<Long> ttrValues = requests.stream()
                .map(DispatchRequest::getTtrMs)
                .filter(v -> v != null)
                .toList();
        List<Long> degradedReassignmentValues = degradedReassignmentTimes(assignments);
        long totalRequests = requests.size();
        long pending = requestRepository.countByStatus(RequestStatus.PENDING);
        long proposed = requestRepository.countByStatus(RequestStatus.PROPOSED);
        long accepted = requestRepository.countByStatus(RequestStatus.ACCEPTED);
        long closed = requestRepository.countByStatus(RequestStatus.CLOSED);
        long failed = requestRepository.countByStatus(RequestStatus.FAILED);
        long totalAssignments = assignments.size();
        long refused = assignmentRepository.countByOutcome(AssignmentOutcome.REFUSED);
        long timedOut = assignmentRepository.countByOutcome(AssignmentOutcome.TIMED_OUT);

        return new DispatchMetricsResponse(
                totalRequests,
                pending,
                proposed,
                accepted,
                closed,
                failed,
                totalAssignments,
                refused,
                timedOut,
                percent(accepted + closed, totalRequests),
                percent(refused, totalAssignments),
                percent(timedOut, totalAssignments),
                percent(failed, totalRequests),
                average(ttfaValues),
                percentile(ttfaValues, 0.95),
                average(ttrValues),
                percentile(ttrValues, 0.95),
                average(degradedReassignmentValues),
                percentile(degradedReassignmentValues, 0.95),
                gini(professionals.stream().map(Professional::getConsultationsToday).toList())
        );
    }

    private List<Long> degradedReassignmentTimes(List<Assignment> assignments) {
        Map<String, OffsetDateTime> lastFailureByRequest = new HashMap<>();
        List<Long> durations = new ArrayList<>();

        for (Assignment assignment : assignments) {
            String requestId = assignment.getRequest().getId();
            OffsetDateTime previousFailure = lastFailureByRequest.get(requestId);
            OffsetDateTime proposedAt = assignment.getProposedAt();
            if (previousFailure != null && proposedAt != null && !proposedAt.isBefore(previousFailure)) {
                durations.add(Duration.between(previousFailure, proposedAt).toMillis());
                lastFailureByRequest.remove(requestId);
            }

            OffsetDateTime failureAt = failureAt(assignment);
            if (failureAt != null) {
                lastFailureByRequest.put(requestId, failureAt);
            }
        }
        return durations;
    }

    private OffsetDateTime failureAt(Assignment assignment) {
        if (assignment.getOutcome() == AssignmentOutcome.REFUSED) {
            return assignment.getRefusedAt();
        }
        if (assignment.getOutcome() == AssignmentOutcome.TIMED_OUT) {
            return assignment.getTimedOutAt();
        }
        return null;
    }

    private double percent(long value, long total) {
        return total == 0 ? 0.0 : round((double) value * 100.0 / total);
    }

    private Double average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        return round(values.stream().mapToLong(Long::longValue).average().orElse(0.0));
    }

    private Double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int nearestRank = (int) Math.ceil(percentile * sorted.size());
        return round(sorted.get(Math.max(0, nearestRank - 1)));
    }

    private double gini(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0);
        if (mean == 0.0) {
            return 0.0;
        }
        double sumAbsDiff = 0.0;
        for (int left : values) {
            for (int right : values) {
                sumAbsDiff += Math.abs(left - right);
            }
        }
        return round(sumAbsDiff / (2.0 * values.size() * values.size() * mean));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

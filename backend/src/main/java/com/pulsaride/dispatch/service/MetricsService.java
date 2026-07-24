package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.DispatchMetricsResponse;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.util.List;
import org.springframework.stereotype.Service;

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

    public DispatchMetricsResponse summary() {
        List<DispatchRequest> requests = requestRepository.findAll();
        List<Professional> professionals = professionalRepository.findAll();
        long totalRequests = requests.size();
        long pending = requestRepository.countByStatus(RequestStatus.PENDING);
        long proposed = requestRepository.countByStatus(RequestStatus.PROPOSED);
        long accepted = requestRepository.countByStatus(RequestStatus.ACCEPTED);
        long closed = requestRepository.countByStatus(RequestStatus.CLOSED);
        long failed = requestRepository.countByStatus(RequestStatus.FAILED);
        long totalAssignments = assignmentRepository.count();
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
                average(requests.stream().map(DispatchRequest::getTtfaMs).filter(v -> v != null).toList()),
                average(requests.stream().map(DispatchRequest::getTtrMs).filter(v -> v != null).toList()),
                gini(professionals.stream().map(Professional::getConsultationsToday).toList())
        );
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

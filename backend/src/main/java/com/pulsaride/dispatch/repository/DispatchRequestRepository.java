package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.RequestStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchRequestRepository extends JpaRepository<DispatchRequest, String> {
    List<DispatchRequest> findByStatusOrderByCreatedAtAsc(RequestStatus status);
    Optional<DispatchRequest> findFirstByStatusOrderByUrgencyScoreDescCreatedAtAsc(RequestStatus status);
    List<DispatchRequest> findByStatusAndProposedAtBefore(RequestStatus status, OffsetDateTime proposedBefore);
    long countByStatus(RequestStatus status);
}

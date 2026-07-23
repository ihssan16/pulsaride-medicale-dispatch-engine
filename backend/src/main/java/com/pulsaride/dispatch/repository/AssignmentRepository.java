package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.Assignment;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import com.pulsaride.dispatch.matching.DispatchStrategy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    Optional<Assignment> findFirstByRequestIdAndOutcomeOrderByProposedAtDesc(
            String requestId,
            AssignmentOutcome outcome
    );

    @EntityGraph(attributePaths = {"request", "professional"})
    List<Assignment> findByRequestIdOrderByProposedAtDesc(String requestId);

    @EntityGraph(attributePaths = {"professional"})
    Optional<Assignment> findFirstByStrategyOrderByProposedAtDescIdDesc(DispatchStrategy strategy);

    @Query("select a from Assignment a join fetch a.request order by a.request.id, a.proposedAt")
    List<Assignment> findAllForMetrics();

    long countByOutcome(AssignmentOutcome outcome);
}

package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.Assignment;
import com.pulsaride.dispatch.domain.AssignmentOutcome;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    Optional<Assignment> findFirstByRequestIdAndOutcomeOrderByProposedAtDesc(
            String requestId,
            AssignmentOutcome outcome
    );

    @EntityGraph(attributePaths = {"request", "professional"})
    List<Assignment> findByRequestIdOrderByProposedAtDesc(String requestId);
    long countByOutcome(AssignmentOutcome outcome);
}

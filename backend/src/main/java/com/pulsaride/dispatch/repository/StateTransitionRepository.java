package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.StateTransition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateTransitionRepository extends JpaRepository<StateTransition, Long> {
    List<StateTransition> findByRequestIdOrderByOccurredAtAsc(String requestId);
}

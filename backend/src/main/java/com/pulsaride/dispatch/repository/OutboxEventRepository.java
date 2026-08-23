package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.OutboxEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findByPublishedFalseOrderByOccurredAtAsc();
    List<OutboxEvent> findByPublishedFalseOrderByOccurredAtAsc(Pageable pageable);
    List<OutboxEvent> findByAggregateIdOrderByOccurredAtAsc(String aggregateId);
}

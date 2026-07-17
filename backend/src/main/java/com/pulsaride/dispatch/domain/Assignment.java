package com.pulsaride.dispatch.domain;

import com.pulsaride.dispatch.matching.DispatchStrategy;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "assignments")
public class Assignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private DispatchRequest request;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id")
    private Professional professional;
    @Enumerated(EnumType.STRING)
    private DispatchStrategy strategy;
    private OffsetDateTime proposedAt;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime refusedAt;
    private OffsetDateTime timedOutAt;
    @Enumerated(EnumType.STRING)
    private AssignmentOutcome outcome;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public DispatchRequest getRequest() { return request; }
    public void setRequest(DispatchRequest request) { this.request = request; }
    public Professional getProfessional() { return professional; }
    public void setProfessional(Professional professional) { this.professional = professional; }
    public DispatchStrategy getStrategy() { return strategy; }
    public void setStrategy(DispatchStrategy strategy) { this.strategy = strategy; }
    public OffsetDateTime getProposedAt() { return proposedAt; }
    public void setProposedAt(OffsetDateTime proposedAt) { this.proposedAt = proposedAt; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public OffsetDateTime getRefusedAt() { return refusedAt; }
    public void setRefusedAt(OffsetDateTime refusedAt) { this.refusedAt = refusedAt; }
    public OffsetDateTime getTimedOutAt() { return timedOutAt; }
    public void setTimedOutAt(OffsetDateTime timedOutAt) { this.timedOutAt = timedOutAt; }
    public AssignmentOutcome getOutcome() { return outcome; }
    public void setOutcome(AssignmentOutcome outcome) { this.outcome = outcome; }
}

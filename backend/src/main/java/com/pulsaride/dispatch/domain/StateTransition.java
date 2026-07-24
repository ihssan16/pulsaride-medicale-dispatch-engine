package com.pulsaride.dispatch.domain;

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
@Table(name = "state_transitions")
public class StateTransition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private DispatchRequest request;
    @Enumerated(EnumType.STRING)
    private RequestStatus fromStatus;
    @Enumerated(EnumType.STRING)
    private RequestStatus toStatus;
    private String reason;
    private OffsetDateTime occurredAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public DispatchRequest getRequest() { return request; }
    public void setRequest(DispatchRequest request) { this.request = request; }
    public RequestStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(RequestStatus fromStatus) { this.fromStatus = fromStatus; }
    public RequestStatus getToStatus() { return toStatus; }
    public void setToStatus(RequestStatus toStatus) { this.toStatus = toStatus; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }
}

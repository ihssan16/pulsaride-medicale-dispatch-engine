package com.pulsaride.dispatch.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "dispatch_requests")
public class DispatchRequest {
    @Id
    private String id;
    private String patientId;
    @Column(columnDefinition = "TEXT")
    private String patientText;
    private String specialtyHint;
    private int urgencyScore;
    @Enumerated(EnumType.STRING)
    private RequestStatus status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_professional_id")
    private Professional assignedProfessional;
    private OffsetDateTime createdAt;
    private OffsetDateTime proposedAt;
    private OffsetDateTime acceptedAt;
    private OffsetDateTime closedAt;
    private Long ttfaMs;
    private Long ttrMs;
    private String failureReason;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getPatientText() { return patientText; }
    public void setPatientText(String patientText) { this.patientText = patientText; }
    public String getSpecialtyHint() { return specialtyHint; }
    public void setSpecialtyHint(String specialtyHint) { this.specialtyHint = specialtyHint; }
    public int getUrgencyScore() { return urgencyScore; }
    public void setUrgencyScore(int urgencyScore) { this.urgencyScore = urgencyScore; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public Professional getAssignedProfessional() { return assignedProfessional; }
    public void setAssignedProfessional(Professional assignedProfessional) { this.assignedProfessional = assignedProfessional; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getProposedAt() { return proposedAt; }
    public void setProposedAt(OffsetDateTime proposedAt) { this.proposedAt = proposedAt; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }
    public Long getTtfaMs() { return ttfaMs; }
    public void setTtfaMs(Long ttfaMs) { this.ttfaMs = ttfaMs; }
    public Long getTtrMs() { return ttrMs; }
    public void setTtrMs(Long ttrMs) { this.ttrMs = ttrMs; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}

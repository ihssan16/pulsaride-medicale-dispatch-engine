package com.pulsaride.dispatch.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "availability_slots")
public class AvailabilitySlot {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id")
    private Professional professional;
    private String specialtyTag;
    @Enumerated(EnumType.STRING)
    private AvailabilitySlotStatus status;
    private String reservedRequestId;
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Professional getProfessional() { return professional; }
    public void setProfessional(Professional professional) { this.professional = professional; }
    public String getSpecialtyTag() { return specialtyTag; }
    public void setSpecialtyTag(String specialtyTag) { this.specialtyTag = specialtyTag; }
    public AvailabilitySlotStatus getStatus() { return status; }
    public void setStatus(AvailabilitySlotStatus status) { this.status = status; }
    public String getReservedRequestId() { return reservedRequestId; }
    public void setReservedRequestId(String reservedRequestId) { this.reservedRequestId = reservedRequestId; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}

package com.pulsaride.dispatch.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

@Entity
@Table(name = "professionals")
public class Professional {
    @Id
    private String id;
    private String name;
    private String specialtyTag;
    private int experienceYears;
    @Column(columnDefinition = "TEXT")
    private String profileText;
    private int quotaMaxPerHour;
    @Enumerated(EnumType.STRING)
    private ProfessionalStatus status;
    private int consultationsToday;
    private double load;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSpecialtyTag() { return specialtyTag; }
    public void setSpecialtyTag(String specialtyTag) { this.specialtyTag = specialtyTag; }
    public int getExperienceYears() { return experienceYears; }
    public void setExperienceYears(int experienceYears) { this.experienceYears = experienceYears; }
    public String getProfileText() { return profileText; }
    public void setProfileText(String profileText) { this.profileText = profileText; }
    public int getQuotaMaxPerHour() { return quotaMaxPerHour; }
    public void setQuotaMaxPerHour(int quotaMaxPerHour) { this.quotaMaxPerHour = quotaMaxPerHour; }
    public ProfessionalStatus getStatus() { return status; }
    public void setStatus(ProfessionalStatus status) { this.status = status; }
    public int getConsultationsToday() { return consultationsToday; }
    public void setConsultationsToday(int consultationsToday) { this.consultationsToday = consultationsToday; }
    public double getLoad() { return load; }
    public void setLoad(double load) { this.load = load; }
}

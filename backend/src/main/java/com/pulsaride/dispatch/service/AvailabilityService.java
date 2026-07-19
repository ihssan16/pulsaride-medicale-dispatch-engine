package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.AvailabilitySummaryResponse;
import com.pulsaride.dispatch.api.SpecialtyAvailabilityResponse;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {
    private final ProfessionalRepository repository;

    public AvailabilityService(ProfessionalRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AvailabilitySummaryResponse summary() {
        List<Professional> professionals = repository.findAll();
        List<SpecialtyAvailabilityResponse> specialties = professionals.stream()
                .collect(Collectors.groupingBy(Professional::getSpecialtyTag))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> summarizeSpecialty(entry.getKey(), entry.getValue()))
                .toList();

        return new AvailabilitySummaryResponse(
                professionals.size(),
                count(professionals, ProfessionalStatus.AVAILABLE),
                count(professionals, ProfessionalStatus.PROPOSED),
                count(professionals, ProfessionalStatus.BUSY),
                count(professionals, ProfessionalStatus.BREAK),
                count(professionals, ProfessionalStatus.OFFLINE),
                availabilityRate(professionals),
                availableCapacity(professionals),
                specialties
        );
    }

    @Transactional(readOnly = true)
    public SpecialtyAvailabilityResponse specialty(String specialtyTag) {
        List<Professional> professionals = repository.findAll()
                .stream()
                .filter(professional -> professional.getSpecialtyTag().equalsIgnoreCase(specialtyTag))
                .toList();
        return summarizeSpecialty(specialtyTag, professionals);
    }

    private SpecialtyAvailabilityResponse summarizeSpecialty(
            String specialtyTag,
            List<Professional> professionals
    ) {
        List<String> availableIds = professionals.stream()
                .filter(professional -> professional.getStatus() == ProfessionalStatus.AVAILABLE)
                .sorted(Comparator
                        .comparingDouble(Professional::getLoad)
                        .thenComparing(Comparator.comparingInt(Professional::getExperienceYears).reversed())
                        .thenComparing(Professional::getId))
                .map(Professional::getId)
                .toList();

        return new SpecialtyAvailabilityResponse(
                specialtyTag,
                professionals.size(),
                count(professionals, ProfessionalStatus.AVAILABLE),
                count(professionals, ProfessionalStatus.PROPOSED),
                count(professionals, ProfessionalStatus.BUSY),
                count(professionals, ProfessionalStatus.BREAK),
                count(professionals, ProfessionalStatus.OFFLINE),
                availabilityRate(professionals),
                availableCapacity(professionals),
                averageLoad(professionals),
                availableIds
        );
    }

    private long count(List<Professional> professionals, ProfessionalStatus status) {
        return professionals.stream()
                .filter(professional -> professional.getStatus() == status)
                .count();
    }

    private double availabilityRate(List<Professional> professionals) {
        if (professionals.isEmpty()) {
            return 0.0;
        }
        return round2(count(professionals, ProfessionalStatus.AVAILABLE) * 100.0 / professionals.size());
    }

    private int availableCapacity(List<Professional> professionals) {
        return professionals.stream()
                .filter(professional -> professional.getStatus() == ProfessionalStatus.AVAILABLE)
                .mapToInt(professional -> Math.max(
                        0,
                        professional.getQuotaMaxPerHour() - professional.getConsultationsToday()
                ))
                .sum();
    }

    private double averageLoad(List<Professional> professionals) {
        return professionals.stream()
                .mapToDouble(Professional::getLoad)
                .average()
                .stream()
                .map(this::round2)
                .findFirst()
                .orElse(0.0);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

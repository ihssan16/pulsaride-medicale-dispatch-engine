package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.AvailabilitySummaryResponse;
import com.pulsaride.dispatch.api.SpecialtyAvailabilityResponse;
import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.AvailabilitySlotStatus;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.repository.AvailabilitySlotRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {
    private final AvailabilitySlotRepository slotRepository;
    private final ProfessionalRepository repository;

    public AvailabilityService(AvailabilitySlotRepository slotRepository, ProfessionalRepository repository) {
        this.slotRepository = slotRepository;
        this.repository = repository;
    }

    @Transactional
    public AvailabilitySummaryResponse summary() {
        ensureSlotsForExistingProfessionals();
        List<AvailabilitySlot> slots = slotRepository.findAll();
        List<SpecialtyAvailabilityResponse> specialties = slots.stream()
                .collect(Collectors.groupingBy(AvailabilitySlot::getSpecialtyTag))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> summarizeSpecialty(entry.getKey(), entry.getValue()))
                .toList();

        return new AvailabilitySummaryResponse(
                slots.size(),
                count(slots, AvailabilitySlotStatus.AVAILABLE),
                count(slots, AvailabilitySlotStatus.RESERVED),
                count(slots, AvailabilitySlotStatus.BUSY),
                count(slots, AvailabilitySlotStatus.BREAK),
                count(slots, AvailabilitySlotStatus.OFFLINE),
                availabilityRate(slots),
                availableCapacity(slots),
                specialties
        );
    }

    @Transactional
    public SpecialtyAvailabilityResponse specialty(String specialtyTag) {
        ensureSlotsForExistingProfessionals();
        List<AvailabilitySlot> slots = slotRepository.findAll()
                .stream()
                .filter(slot -> slot.getSpecialtyTag().equalsIgnoreCase(specialtyTag))
                .toList();
        return summarizeSpecialty(specialtyTag, slots);
    }

    private SpecialtyAvailabilityResponse summarizeSpecialty(
            String specialtyTag,
            List<AvailabilitySlot> slots
    ) {
        List<AvailabilitySlot> availableSlots = slots.stream()
                .filter(slot -> slot.getStatus() == AvailabilitySlotStatus.AVAILABLE)
                .sorted(Comparator
                        .comparingDouble((AvailabilitySlot slot) -> slot.getProfessional().getLoad())
                        .thenComparing(Comparator.comparingInt(
                                (AvailabilitySlot slot) -> slot.getProfessional().getExperienceYears()
                        ).reversed())
                        .thenComparing(AvailabilitySlot::getId))
                .toList();

        return new SpecialtyAvailabilityResponse(
                specialtyTag,
                slots.size(),
                count(slots, AvailabilitySlotStatus.AVAILABLE),
                count(slots, AvailabilitySlotStatus.RESERVED),
                count(slots, AvailabilitySlotStatus.BUSY),
                count(slots, AvailabilitySlotStatus.BREAK),
                count(slots, AvailabilitySlotStatus.OFFLINE),
                availabilityRate(slots),
                availableCapacity(slots),
                averageLoad(slots),
                availableSlots.stream().map(AvailabilitySlot::getId).toList(),
                availableSlots.stream().map(slot -> slot.getProfessional().getId()).toList()
        );
    }

    private long count(List<AvailabilitySlot> slots, AvailabilitySlotStatus status) {
        return slots.stream()
                .filter(slot -> slot.getStatus() == status)
                .count();
    }

    private double availabilityRate(List<AvailabilitySlot> slots) {
        if (slots.isEmpty()) {
            return 0.0;
        }
        return round2(count(slots, AvailabilitySlotStatus.AVAILABLE) * 100.0 / slots.size());
    }

    private int availableCapacity(List<AvailabilitySlot> slots) {
        return slots.stream()
                .filter(slot -> slot.getStatus() == AvailabilitySlotStatus.AVAILABLE)
                .map(AvailabilitySlot::getProfessional)
                .mapToInt(professional -> Math.max(
                        0,
                        professional.getQuotaMaxPerHour() - professional.getConsultationsToday()
                ))
                .sum();
    }

    private double averageLoad(List<AvailabilitySlot> slots) {
        return slots.stream()
                .map(AvailabilitySlot::getProfessional)
                .mapToDouble(Professional::getLoad)
                .average()
                .stream()
                .map(this::round2)
                .findFirst()
                .orElse(0.0);
    }

    private void ensureSlotsForExistingProfessionals() {
        List<Professional> missing = repository.findAll().stream()
                .filter(professional -> slotRepository.findByProfessionalId(professional.getId()).isEmpty())
                .toList();
        missing.forEach(professional -> {
            AvailabilitySlot slot = new AvailabilitySlot();
            slot.setId("slot_" + professional.getId());
            slot.setProfessional(professional);
            slot.setSpecialtyTag(professional.getSpecialtyTag());
            slot.setStatus(toSlotStatus(professional.getStatus()));
            slotRepository.save(slot);
        });
    }

    private AvailabilitySlotStatus toSlotStatus(com.pulsaride.dispatch.domain.ProfessionalStatus status) {
        return switch (status) {
            case AVAILABLE -> AvailabilitySlotStatus.AVAILABLE;
            case PROPOSED -> AvailabilitySlotStatus.RESERVED;
            case BUSY -> AvailabilitySlotStatus.BUSY;
            case BREAK -> AvailabilitySlotStatus.BREAK;
            case OFFLINE -> AvailabilitySlotStatus.OFFLINE;
        };
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

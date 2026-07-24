package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.CreateProfessionalRequest;
import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.AvailabilitySlotStatus;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.AvailabilitySlotRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfessionalService {
    private final ProfessionalRepository repository;
    private final AvailabilitySlotRepository slotRepository;
    private final DispatchRedisService redisService;

    public ProfessionalService(
            ProfessionalRepository repository,
            AvailabilitySlotRepository slotRepository,
            DispatchRedisService redisService
    ) {
        this.repository = repository;
        this.slotRepository = slotRepository;
        this.redisService = redisService;
    }

    @Transactional
    public Professional create(CreateProfessionalRequest command) {
        Professional professional = new Professional();
        professional.setId(command.id() == null || command.id().isBlank()
                ? "pro_" + UUID.randomUUID()
                : command.id());
        professional.setName(command.name());
        professional.setSpecialtyTag(command.specialtyTag());
        professional.setExperienceYears(command.experienceYears());
        professional.setProfileText(command.profileText());
        professional.setQuotaMaxPerHour(command.quotaMaxPerHour());
        professional.setStatus(command.status());
        professional.setConsultationsToday(0);
        professional.setLoad(0.0);
        Professional saved = repository.save(professional);
        AvailabilitySlot slot = slotRepository.findByProfessionalId(saved.getId()).orElseGet(() -> {
            AvailabilitySlot newSlot = new AvailabilitySlot();
            newSlot.setId("slot_" + saved.getId());
            newSlot.setProfessional(saved);
            return newSlot;
        });
        slot.setSpecialtyTag(saved.getSpecialtyTag());
        slot.setStatus(toSlotStatus(saved.getStatus()));
        slot.setReservedRequestId(null);
        AvailabilitySlot savedSlot = slotRepository.save(slot);
        redisService.syncProfessional(saved);
        redisService.syncAvailabilitySlot(savedSlot);
        return saved;
    }

    @Transactional
    public Professional updateStatus(String id, ProfessionalStatus status) {
        Professional professional = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Professional not found: " + id));
        professional.setStatus(status);
        Professional saved = repository.save(professional);
        AvailabilitySlot slot = slotRepository.findByProfessionalId(saved.getId()).orElseGet(() -> {
            AvailabilitySlot newSlot = new AvailabilitySlot();
            newSlot.setId("slot_" + saved.getId());
            newSlot.setProfessional(saved);
            newSlot.setSpecialtyTag(saved.getSpecialtyTag());
            return newSlot;
        });
        slot.setStatus(toSlotStatus(status));
        if (slot.getStatus() == AvailabilitySlotStatus.AVAILABLE || slot.getStatus() == AvailabilitySlotStatus.OFFLINE) {
            slot.setReservedRequestId(null);
        }
        AvailabilitySlot savedSlot = slotRepository.save(slot);
        redisService.syncProfessional(saved);
        redisService.syncAvailabilitySlot(savedSlot);
        return saved;
    }

    private AvailabilitySlotStatus toSlotStatus(ProfessionalStatus status) {
        return switch (status) {
            case AVAILABLE -> AvailabilitySlotStatus.AVAILABLE;
            case PROPOSED -> AvailabilitySlotStatus.RESERVED;
            case BUSY -> AvailabilitySlotStatus.BUSY;
            case BREAK -> AvailabilitySlotStatus.BREAK;
            case OFFLINE -> AvailabilitySlotStatus.OFFLINE;
        };
    }
}

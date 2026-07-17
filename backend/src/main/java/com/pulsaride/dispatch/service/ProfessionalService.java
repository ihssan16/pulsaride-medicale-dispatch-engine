package com.pulsaride.dispatch.service;

import com.pulsaride.dispatch.api.CreateProfessionalRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfessionalService {
    private final ProfessionalRepository repository;
    private final DispatchRedisService redisService;

    public ProfessionalService(ProfessionalRepository repository, DispatchRedisService redisService) {
        this.repository = repository;
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
        redisService.syncProfessional(saved);
        return saved;
    }

    @Transactional
    public Professional updateStatus(String id, ProfessionalStatus status) {
        Professional professional = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Professional not found: " + id));
        professional.setStatus(status);
        Professional saved = repository.save(professional);
        redisService.syncProfessional(saved);
        return saved;
    }
}

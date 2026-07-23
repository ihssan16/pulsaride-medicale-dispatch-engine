package com.pulsaride.dispatch.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.AvailabilitySlotStatus;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.redis.DispatchRedisService;
import com.pulsaride.dispatch.repository.AvailabilitySlotRepository;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableConfigurationProperties(SeedDataProperties.class)
public class SeedDataLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final SeedDataProperties properties;
    private final ObjectMapper objectMapper;
    private final ProfessionalRepository professionalRepository;
    private final AvailabilitySlotRepository slotRepository;
    private final DispatchRequestRepository requestRepository;
    private final DispatchRedisService redisService;

    public SeedDataLoader(
            SeedDataProperties properties,
            ObjectMapper objectMapper,
            ProfessionalRepository professionalRepository,
            AvailabilitySlotRepository slotRepository,
            DispatchRequestRepository requestRepository,
            DispatchRedisService redisService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.professionalRepository = professionalRepository;
        this.slotRepository = slotRepository;
        this.requestRepository = requestRepository;
        this.redisService = redisService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isEnabled() || professionalRepository.count() > 0 || requestRepository.count() > 0) {
            return;
        }

        Path dataDir = Path.of(properties.getSimulatorDataDir());
        Path professionalsPath = dataDir.resolve("professionals.json");
        Path requestsPath = dataDir.resolve("requests.json");

        if (!Files.exists(professionalsPath) || !Files.exists(requestsPath)) {
            log.warn("Seed data files not found under {}", dataDir.toAbsolutePath());
            return;
        }

        for (JsonNode node : objectMapper.readTree(professionalsPath.toFile())) {
            Professional professional = new Professional();
            professional.setId(node.get("id").asText());
            professional.setName(node.get("name").asText());
            professional.setSpecialtyTag(node.get("specialty_tag").asText());
            professional.setExperienceYears(node.get("experience_years").asInt());
            professional.setProfileText(node.get("profile_text").asText());
            professional.setQuotaMaxPerHour(node.get("quota_max_per_hour").asInt());
            professional.setStatus(ProfessionalStatus.valueOf(node.get("status").asText()));
            professional.setConsultationsToday(node.get("consultations_today").asInt());
            professional.setLoad(node.get("load").asDouble());
            Professional saved = professionalRepository.save(professional);
            AvailabilitySlot slot = new AvailabilitySlot();
            slot.setId("slot_" + saved.getId());
            slot.setProfessional(saved);
            slot.setSpecialtyTag(saved.getSpecialtyTag());
            slot.setStatus(toSlotStatus(saved.getStatus()));
            AvailabilitySlot savedSlot = slotRepository.save(slot);
            redisService.syncProfessional(saved);
            redisService.syncAvailabilitySlot(savedSlot);
        }

        for (JsonNode node : objectMapper.readTree(requestsPath.toFile())) {
            DispatchRequest request = new DispatchRequest();
            request.setId(node.get("id").asText());
            request.setPatientId(node.get("patient_id").asText());
            request.setPatientText(node.get("patient_text").asText());
            request.setSpecialtyHint(node.get("specialty_hint").asText());
            request.setUrgencyScore(node.get("urgency_score").asInt());
            request.setStatus(RequestStatus.valueOf(node.get("status").asText()));
            request.setCreatedAt(parseDateTime(node.get("created_at").asText()));
            requestRepository.save(request);
        }

        log.info("Loaded {} professionals and {} dispatch requests from {}",
                professionalRepository.count(),
                requestRepository.count(),
                dataDir.toAbsolutePath());
    }

    private OffsetDateTime parseDateTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        }
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

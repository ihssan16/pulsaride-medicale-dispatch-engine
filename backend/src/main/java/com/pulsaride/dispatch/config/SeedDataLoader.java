package com.pulsaride.dispatch.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.domain.RequestStatus;
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
    private final DispatchRequestRepository requestRepository;

    public SeedDataLoader(
            SeedDataProperties properties,
            ObjectMapper objectMapper,
            ProfessionalRepository professionalRepository,
            DispatchRequestRepository requestRepository
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.professionalRepository = professionalRepository;
        this.requestRepository = requestRepository;
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
            professionalRepository.save(professional);
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
}

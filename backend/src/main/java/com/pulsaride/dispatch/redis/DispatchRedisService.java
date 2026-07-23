package com.pulsaride.dispatch.redis;

import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.AvailabilitySlotStatus;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DispatchRedisService {
    private static final Logger log = LoggerFactory.getLogger(DispatchRedisService.class);
    private static final String REQUEST_QUEUE = "dispatch:requests:priority";
    private static final String PROFESSIONAL_REGISTRY = "dispatch:professionals:registry";
    private static final String ACTIVE_SLOTS = "availability:slots:";
    private static final String SLOT_HASH = "availability:slot:";

    private final StringRedisTemplate redisTemplate;

    public DispatchRedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void enqueue(DispatchRequest request) {
        runRedis("enqueue request", () -> {
            double score = request.getCreatedAt().toEpochSecond() - (request.getUrgencyScore() * 3600.0);
            redisTemplate.opsForZSet().add(REQUEST_QUEUE, request.getId(), score);
        });
    }

    public void removeFromQueue(String requestId) {
        runRedis("remove request", () -> redisTemplate.opsForZSet().remove(REQUEST_QUEUE, requestId));
    }

    public boolean acquireAssignmentLock(String requestId) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    "dispatch:lock:" + requestId,
                    Instant.now().toString(),
                    Duration.ofSeconds(30)
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            log.warn("Redis unavailable while acquiring lock; continuing without distributed lock");
            return true;
        }
    }

    public void releaseAssignmentLock(String requestId) {
        runRedis("release assignment lock", () -> redisTemplate.delete("dispatch:lock:" + requestId));
    }

    public boolean acquireSlotLock(String slotId, String requestId) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    "availability:slot:lock:" + slotId,
                    requestId,
                    Duration.ofSeconds(30)
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ex) {
            log.warn("Redis unavailable while acquiring slot lock; continuing with database reservation");
            return true;
        }
    }

    public void releaseSlotLock(String slotId) {
        runRedis("release slot lock", () -> redisTemplate.delete("availability:slot:lock:" + slotId));
    }

    public void syncProfessional(Professional professional) {
        runRedis("sync professional", () -> redisTemplate.opsForHash().putAll(
                PROFESSIONAL_REGISTRY + ":" + professional.getId(),
                Map.of(
                        "id", professional.getId(),
                        "status", professional.getStatus().name(),
                        "specialtyTag", professional.getSpecialtyTag(),
                        "load", Double.toString(professional.getLoad())
                )
        ));
    }

    public void syncAvailabilitySlot(AvailabilitySlot slot) {
        runRedis("sync availability slot", () -> {
            redisTemplate.opsForHash().putAll(
                    SLOT_HASH + slot.getId(),
                    Map.of(
                            "id", slot.getId(),
                            "professionalId", slot.getProfessional().getId(),
                            "specialtyTag", slot.getSpecialtyTag(),
                            "status", slot.getStatus().name(),
                            "reservedRequestId", slot.getReservedRequestId() == null
                                    ? ""
                                    : slot.getReservedRequestId(),
                            "load", Double.toString(slot.getProfessional().getLoad())
                    )
            );
            String specialtyKey = ACTIVE_SLOTS + slot.getSpecialtyTag().toLowerCase(Locale.ROOT);
            if (slot.getStatus() == AvailabilitySlotStatus.AVAILABLE) {
                redisTemplate.opsForZSet().add(specialtyKey, slot.getId(), slot.getProfessional().getLoad());
            } else {
                redisTemplate.opsForZSet().remove(specialtyKey, slot.getId());
            }
        });
    }

    private void runRedis(String action, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            log.warn("Redis unavailable during {}; database state remains authoritative", action);
        }
    }
}

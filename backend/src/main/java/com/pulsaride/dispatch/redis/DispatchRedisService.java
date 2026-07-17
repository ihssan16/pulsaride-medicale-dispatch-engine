package com.pulsaride.dispatch.redis;

import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import java.time.Duration;
import java.time.Instant;
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

    private void runRedis(String action, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            log.warn("Redis unavailable during {}; database state remains authoritative", action);
        }
    }
}

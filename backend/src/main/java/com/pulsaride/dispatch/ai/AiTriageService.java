package com.pulsaride.dispatch.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulsaride.dispatch.api.TriageResponse;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiTriageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiTriageService.class);
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*(jour|jours|j)");

    private final String mode;
    private final URI externalPredictUri;
    private final URI openAiResponsesUri;
    private final String openAiApiKey;
    private final String openAiModel;
    private final boolean fallbackEnabled;
    private final ObjectMapper objectMapper;

    public AiTriageService(
            @Value("${pulsaride.ai.mode:mock}") String mode,
            @Value("${pulsaride.ai.external-url:http://localhost:8000}") String externalUrl,
            @Value("${pulsaride.ai.fallback-enabled:true}") boolean fallbackEnabled,
            @Value("${pulsaride.ai.openai.base-url:https://api.openai.com/v1}") String openAiBaseUrl,
            @Value("${pulsaride.ai.openai.api-key:}") String openAiApiKey,
            @Value("${pulsaride.ai.openai.model:gpt-4o-mini}") String openAiModel,
            ObjectMapper objectMapper
    ) {
        this.mode = mode;
        this.externalPredictUri = URI.create(stripTrailingSlash(externalUrl) + "/predict");
        this.openAiResponsesUri = URI.create(stripTrailingSlash(openAiBaseUrl) + "/responses");
        this.openAiApiKey = openAiApiKey;
        this.openAiModel = openAiModel;
        this.fallbackEnabled = fallbackEnabled;
        this.objectMapper = objectMapper;
    }

    public TriageResponse triage(String text) {
        try {
            if ("external".equalsIgnoreCase(mode) || "darija".equalsIgnoreCase(mode)) {
                return triageWithExternalService(text);
            }
            if ("openai".equalsIgnoreCase(mode)) {
                return triageWithOpenAi(text);
            }
        } catch (IOException | RuntimeException ex) {
            if (!fallbackEnabled) {
                throw new IllegalStateException("AI triage provider is unavailable", ex);
            }
            LOGGER.warn("AI triage provider '{}' failed, using local fallback: {}", mode, ex.getMessage());
            return triageWithRules(text, mode.toLowerCase(Locale.ROOT) + "-fallback");
        }
        return triageWithRules(text, mode);
    }

    private TriageResponse triageWithExternalService(String text) throws IOException {
        String requestBody = objectMapper.writeValueAsString(Map.of("message", text));
        byte[] requestBytes = requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) externalPredictUri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setFixedLengthStreamingMode(requestBytes.length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(requestBytes);
        }

        int statusCode = connection.getResponseCode();
        String responseBody = readResponseBody(connection, statusCode);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(
                    "External AI triage returned HTTP " + statusCode + ": " + responseBody
            );
        }

        DarijaPredictResponse prediction = objectMapper.readValue(responseBody, DarijaPredictResponse.class);
        int urgencyScore = mapUrgency(prediction.urgency());
        String specialty = mapSpecialty(prediction.predicted_specialty());
        List<String> symptoms = prediction.symptoms() == null || prediction.symptoms().isEmpty()
                ? List.of("symptome_general")
                : prediction.symptoms();
        String normalized = normalize(text);
        String ageGroup = containsAny(normalized, "enfant", "fils", "fille", "bebe") ? "enfant" : "adulte";

        return new TriageResponse(
                symptoms,
                extractDurationDays(normalized),
                urgencyScore,
                ageGroup,
                specialty,
                urgencyScore,
                "external",
                prediction.specialty_confidence(),
                prediction.urgency_reason(),
                "darija-health-nlp"
        );
    }

    private String readResponseBody(HttpURLConnection connection, int statusCode) throws IOException {
        InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private TriageResponse triageWithOpenAi(String text) throws IOException {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is required when AI_MODE=openai");
        }

        String requestBody = objectMapper.writeValueAsString(openAiRequestBody(text));
        byte[] requestBytes = requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) openAiResponsesUri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + openAiApiKey);
        connection.setFixedLengthStreamingMode(requestBytes.length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(requestBytes);
        }

        int statusCode = connection.getResponseCode();
        String responseBody = readResponseBody(connection, statusCode);
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("OpenAI triage returned HTTP " + statusCode + ": " + responseBody);
        }

        String outputText = extractOpenAiOutputText(responseBody);
        OpenAiTriagePayload prediction = objectMapper.readValue(outputText, OpenAiTriagePayload.class);
        return applySafetyFloor(text, prediction);
    }

    private Map<String, Object> openAiRequestBody(String text) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of(
                        "symptoms",
                        "durationDays",
                        "severity",
                        "ageGroup",
                        "specialtyHint",
                        "urgencyScore",
                        "confidence",
                        "urgencyReason"
                ),
                "properties", Map.of(
                        "symptoms", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                        ),
                        "durationDays", Map.of("type", List.of("integer", "null")),
                        "severity", Map.of("type", "integer", "minimum", 0, "maximum", 3),
                        "ageGroup", Map.of("type", "string", "enum", List.of("enfant", "adulte")),
                        "specialtyHint", Map.of("type", "string"),
                        "urgencyScore", Map.of("type", "integer", "minimum", 0, "maximum", 3),
                        "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1),
                        "urgencyReason", Map.of("type", "string")
                )
        );

        String developerPrompt = """
                You are a medical triage extraction component for an internship dispatch prototype.
                Extract structured fields from Moroccan Darija, Arabic, French, or mixed patient text.
                This is not a diagnosis. Use urgencyScore 0..3 where 3 means urgent red-flag.
                Prefer these normalized specialties when possible: generaliste, cardiologie, dermatologie,
                gastroenterologie, gynecologie, neurologie, orl, pediatrie, pneumologie, psychiatrie, urgence.
                """;

        return Map.of(
                "model", openAiModel,
                "input", List.of(
                        Map.of("role", "developer", "content", developerPrompt),
                        Map.of("role", "user", "content", text)
                ),
                "text", Map.of(
                        "format", Map.of(
                                "type", "json_schema",
                                "name", "pulsaride_triage",
                                "strict", true,
                                "schema", schema
                        )
                )
        );
    }

    private String extractOpenAiOutputText(String responseBody) throws IOException {
        var root = objectMapper.readTree(responseBody);
        if (root.hasNonNull("output_text")) {
            return root.get("output_text").asText();
        }

        var output = root.path("output");
        if (output.isArray()) {
            for (var item : output) {
                var content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (var contentItem : content) {
                    if (contentItem.hasNonNull("text")) {
                        return contentItem.get("text").asText();
                    }
                }
            }
        }
        throw new IllegalStateException("OpenAI response did not contain output text");
    }

    private TriageResponse applySafetyFloor(String text, OpenAiTriagePayload prediction) {
        TriageResponse safetyFloor = triageWithRules(text, "openai-safety-floor");
        int urgency = Math.max(clampUrgency(prediction.urgencyScore()), safetyFloor.urgencyScore());
        int severity = Math.max(clampUrgency(prediction.severity()), safetyFloor.severity());
        boolean safetyFloorRaisedUrgency = urgency > clampUrgency(prediction.urgencyScore());
        List<String> symptoms = prediction.symptoms() == null || prediction.symptoms().isEmpty()
                ? safetyFloor.symptoms()
                : prediction.symptoms();
        String ageGroup = normalizeAgeGroup(prediction.ageGroup(), safetyFloor.ageGroup());
        String specialty = safetyFloorRaisedUrgency
                ? safetyFloor.specialtyHint()
                : normalizeSpecialty(prediction.specialtyHint(), safetyFloor.specialtyHint());
        Integer durationDays = prediction.durationDays() == null
                ? safetyFloor.durationDays()
                : prediction.durationDays();

        return new TriageResponse(
                symptoms,
                durationDays,
                severity,
                ageGroup,
                specialty,
                urgency,
                safetyFloorRaisedUrgency ? "openai+safety-floor" : "openai",
                prediction.confidence(),
                prediction.urgencyReason(),
                "openai:" + openAiModel
        );
    }

    private TriageResponse triageWithRules(String text, String responseMode) {
        String normalized = normalize(text);
        List<String> symptoms = new ArrayList<>();

        if (containsAny(normalized, "gorge", "angine")) {
            symptoms.add("pharyngite");
        }
        if (containsAny(normalized, "fievre", "temperature", "38", "39", "40")) {
            symptoms.add("hyperthermie");
        }
        if (containsAny(normalized, "palpitation", "coeur", "poitrine")) {
            symptoms.add("symptome_cardiaque");
        }
        if (containsAny(normalized, "bouton", "plaque", "demangeaison", "peau")) {
            symptoms.add("symptome_dermatologique");
        }
        if (containsAny(normalized, "anxieux", "angoisse", "depression", "stress")) {
            symptoms.add("symptome_psy");
        }

        String ageGroup = containsAny(normalized, "enfant", "fils", "fille", "bebe") ? "enfant" : "adulte";
        String specialty = inferSpecialty(normalized, ageGroup);
        int urgency = inferUrgency(normalized, specialty);
        Integer durationDays = extractDurationDays(normalized);

        return new TriageResponse(
                symptoms.isEmpty() ? List.of("symptome_general") : symptoms,
                durationDays,
                urgency,
                ageGroup,
                specialty,
                urgency,
                responseMode,
                null,
                "Local deterministic fallback rules",
                "pulsaride-rules"
        );
    }

    private String inferSpecialty(String normalized, String ageGroup) {
        if ("enfant".equals(ageGroup)) {
            return "pediatrie";
        }
        if (containsAny(normalized, "palpitation", "coeur", "poitrine", "tension")) {
            return "cardiologie";
        }
        if (containsAny(normalized, "peau", "bouton", "plaque", "demangeaison")) {
            return "dermatologie";
        }
        if (containsAny(normalized, "angoisse", "anxieux", "depression", "stress")) {
            return "psychiatrie";
        }
        if (containsAny(normalized, "gorge", "oreille", "sinus", "nez")) {
            return "orl";
        }
        return "generaliste";
    }

    private int inferUrgency(String normalized, String specialty) {
        if ("cardiologie".equals(specialty) || containsAny(normalized, "poitrine", "essoufflement")) {
            return 3;
        }
        if ("pediatrie".equals(specialty) || "psychiatrie".equals(specialty)) {
            return 2;
        }
        if (containsAny(normalized, "fievre", "douleur", "enceinte")) {
            return 1;
        }
        return 0;
    }

    private Integer extractDurationDays(String normalized) {
        var matcher = DAYS_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private String mapSpecialty(String value) {
        if (value == null || value.isBlank()) {
            return "generaliste";
        }
        String normalized = normalize(value).replace("-", " ").replace("_", " ").trim();
        return switch (normalized) {
            case "cardiology", "cardiologie" -> "cardiologie";
            case "dermatology", "dermatologie" -> "dermatologie";
            case "gastroenterology", "gastro enterology", "gastroenterologie" -> "gastroenterologie";
            case "neurology", "neurologie" -> "neurologie";
            case "obstetrics and gynecology", "obstetrics gynecology", "gynecology", "gynecologie",
                    "obstetrique", "obstetrique et gynecologie" -> "gynecologie";
            case "pediatrics", "paediatrics", "pediatric", "pediatrie" -> "pediatrie";
            case "psychiatry", "psychiatrie" -> "psychiatrie";
            case "pulmonology", "pneumologie" -> "pneumologie";
            case "ent", "orl" -> "orl";
            case "radiology", "radiologie" -> "radiologie";
            case "emergency", "urgence" -> "urgence";
            case "general practice", "generaliste", "general medicine", "medecine generale" -> "generaliste";
            default -> normalized.replace(" ", "_");
        };
    }

    private int mapUrgency(String value) {
        if (value == null) {
            return 0;
        }
        return switch (normalize(value).trim()) {
            case "high", "urgent", "emergency" -> 3;
            case "medium", "moderate" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    private int clampUrgency(Integer value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(3, value));
    }

    private String normalizeAgeGroup(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = normalize(value);
        return "enfant".equals(normalized) ? "enfant" : "adulte";
    }

    private String normalizeSpecialty(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = mapSpecialty(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DarijaPredictResponse(
            String predicted_specialty,
            Double specialty_confidence,
            String urgency,
            String urgency_reason,
            List<String> symptoms
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiTriagePayload(
            List<String> symptoms,
            Integer durationDays,
            Integer severity,
            String ageGroup,
            String specialtyHint,
            Integer urgencyScore,
            Double confidence,
            String urgencyReason
    ) {
    }
}

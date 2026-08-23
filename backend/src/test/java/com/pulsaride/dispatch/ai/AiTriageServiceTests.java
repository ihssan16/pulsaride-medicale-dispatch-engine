package com.pulsaride.dispatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AiTriageServiceTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void externalModeMapsDarijaPredictionToPulsarideTriageResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(new String(requestBody, StandardCharsets.UTF_8)).contains("message");

            byte[] responseBody = """
                    {
                      "input_text": "kanhess b douleur thoracique",
                      "normalized_text": "kanhess b douleur thoracique",
                      "predicted_specialty": "Cardiology",
                      "specialty_confidence": 0.91,
                      "urgency": "high",
                      "urgency_reason": "Chest pain red flag detected.",
                      "symptoms": ["chest_pain"],
                      "recommendation": "Seek urgent medical advice.",
                      "disclaimer": "Not a diagnosis."
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AiTriageService service = new AiTriageService(
                    "external",
                    "http://localhost:" + port,
                    false,
                    "https://api.openai.com/v1",
                    "",
                    "gpt-4o-mini",
                    objectMapper
            );

            var response = service.triage("kanhess b douleur thoracique depuis 2 jours");

            assertThat(response.mode()).isEqualTo("external");
            assertThat(response.sourceModel()).isEqualTo("darija-health-nlp");
            assertThat(response.specialtyHint()).isEqualTo("cardiologie");
            assertThat(response.urgencyScore()).isEqualTo(3);
            assertThat(response.confidence()).isEqualTo(0.91);
            assertThat(response.urgencyReason()).contains("Chest pain");
            assertThat(response.symptoms()).containsExactly("chest_pain");
            assertThat(response.durationDays()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalModeAppliesSafetyFloorToDarijaChestAndBreathingTerms() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> {
            byte[] responseBody = """
                    {
                      "predicted_specialty": "Cardiology",
                      "specialty_confidence": 0.98,
                      "urgency": "unknown",
                      "urgency_reason": "No clear urgency signal was detected.",
                      "symptoms": []
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AiTriageService service = new AiTriageService(
                    "external",
                    "http://localhost:" + port,
                    false,
                    "https://api.openai.com/v1",
                    "",
                    "gpt-4o-mini",
                    objectMapper
            );

            var response = service.triage("kanhess b douleur f sdri w ma9aderch ntnefess");

            assertThat(response.mode()).isEqualTo("external+safety-floor");
            assertThat(response.sourceModel()).isEqualTo("darija-health-nlp");
            assertThat(response.specialtyHint()).isEqualTo("cardiologie");
            assertThat(response.urgencyScore()).isEqualTo(3);
            assertThat(response.severity()).isEqualTo(3);
            assertThat(response.confidence()).isEqualTo(0.98);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalModeFallsBackToLocalRulesWhenConfigured() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/predict", exchange -> {
            byte[] responseBody = "temporary failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AiTriageService service = new AiTriageService(
                    "external",
                    "http://localhost:" + port,
                    true,
                    "https://api.openai.com/v1",
                    "",
                    "gpt-4o-mini",
                    objectMapper
            );

            var response = service.triage("Douleur poitrine et essoufflement depuis 1 jour");

            assertThat(response.mode()).isEqualTo("external-fallback");
            assertThat(response.sourceModel()).isEqualTo("pulsaride-rules");
            assertThat(response.specialtyHint()).isEqualTo("cardiologie");
            assertThat(response.urgencyScore()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiModeMapsStructuredOutputAndKeepsSafetyFloor() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String requestJson = new String(requestBody, StandardCharsets.UTF_8);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            assertThat(requestJson).contains("\"model\":\"test-model\"");
            assertThat(requestJson).contains("\"json_schema\"");
            assertThat(requestJson).contains("douleur poitrine");

            byte[] responseBody = """
                    {
                      "output": [
                        {
                          "type": "message",
                          "content": [
                            {
                              "type": "output_text",
                              "text": "{\\"symptoms\\":[\\"chest_pain\\"],\\"durationDays\\":2,\\"severity\\":0,\\"ageGroup\\":\\"adulte\\",\\"specialtyHint\\":\\"generaliste\\",\\"urgencyScore\\":0,\\"confidence\\":0.42,\\"urgencyReason\\":\\"Model underestimated this synthetic case.\\"}"
                            }
                          ]
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AiTriageService service = new AiTriageService(
                    "openai",
                    "http://localhost:8000",
                    false,
                    "http://localhost:" + port,
                    "test-key",
                    "test-model",
                    objectMapper
            );

            var response = service.triage("douleur poitrine et essoufflement depuis 2 jours");

            assertThat(response.mode()).isEqualTo("openai+safety-floor");
            assertThat(response.sourceModel()).isEqualTo("openai:test-model");
            assertThat(response.specialtyHint()).isEqualTo("cardiologie");
            assertThat(response.urgencyScore()).isEqualTo(3);
            assertThat(response.severity()).isEqualTo(3);
            assertThat(response.confidence()).isEqualTo(0.42);
            assertThat(response.durationDays()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiModeFallsBackToLocalRulesWhenApiKeyIsMissing() {
        AiTriageService service = new AiTriageService(
                "openai",
                "http://localhost:8000",
                true,
                "https://api.openai.com/v1",
                "",
                "gpt-4o-mini",
                objectMapper
        );

        var response = service.triage("Douleur poitrine et essoufflement depuis 1 jour");

        assertThat(response.mode()).isEqualTo("openai-fallback");
        assertThat(response.sourceModel()).isEqualTo("pulsaride-rules");
        assertThat(response.specialtyHint()).isEqualTo("cardiologie");
        assertThat(response.urgencyScore()).isEqualTo(3);
    }
}

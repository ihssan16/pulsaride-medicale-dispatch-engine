package com.pulsaride.dispatch.ai;

import com.pulsaride.dispatch.api.TriageResponse;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiTriageService {
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*(jour|jours|j)");

    private final String mode;

    public AiTriageService(@Value("${pulsaride.ai.mode:mock}") String mode) {
        this.mode = mode;
    }

    public TriageResponse triage(String text) {
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
                mode
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
}

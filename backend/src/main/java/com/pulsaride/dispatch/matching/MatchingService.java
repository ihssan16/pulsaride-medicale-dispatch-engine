package com.pulsaride.dispatch.matching;

import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MatchingService {
    private final ProfessionalRepository professionalRepository;

    public MatchingService(ProfessionalRepository professionalRepository) {
        this.professionalRepository = professionalRepository;
    }

    public Optional<Professional> select(DispatchRequest request, DispatchStrategy strategy) {
        List<Professional> available = professionalRepository
                .findByStatusOrderByLoadAscExperienceYearsDesc(ProfessionalStatus.AVAILABLE);

        return switch (strategy) {
            case S1 -> available.stream().findFirst();
            case S2 -> available.stream()
                    .filter(pro -> pro.getSpecialtyTag().equalsIgnoreCase(request.getSpecialtyHint()))
                    .findFirst();
            case S3 -> available.stream().max(Comparator.comparingDouble(pro -> classicScore(request, pro)));
            case S4 -> available.stream().max(Comparator.comparingDouble(pro -> aiScore(request, pro)));
        };
    }

    private double classicScore(DispatchRequest request, Professional professional) {
        double tag = professional.getSpecialtyTag().equalsIgnoreCase(request.getSpecialtyHint()) ? 1.0 : 0.0;
        double load = 1.0 - Math.min(1.0, professional.getLoad());
        return 0.45 + (tag * 0.35) + (load * 0.20);
    }

    private double aiScore(DispatchRequest request, Professional professional) {
        double similarity = lexicalSimilarity(request.getPatientText(), professional.getProfileText());
        double load = 1.0 - Math.min(1.0, professional.getLoad());
        return (similarity * 0.50) + 0.30 + (load * 0.20);
    }

    private double lexicalSimilarity(String patientText, String profileText) {
        Set<String> patientTerms = terms(patientText);
        Set<String> profileTerms = terms(profileText);
        if (patientTerms.isEmpty() || profileTerms.isEmpty()) {
            return 0.0;
        }
        long overlap = patientTerms.stream().filter(profileTerms::contains).count();
        return (double) overlap / Math.sqrt(patientTerms.size() * profileTerms.size());
    }

    private Set<String> terms(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        Set<String> ignored = Set.of("avec", "depuis", "pour", "dans", "des", "les", "une", "sur", "j", "ai");
        Set<String> result = new HashSet<>();
        Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(term -> term.length() > 2)
                .filter(term -> !ignored.contains(term))
                .forEach(result::add);
        return result;
    }
}

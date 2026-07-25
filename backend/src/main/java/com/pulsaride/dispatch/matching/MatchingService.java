package com.pulsaride.dispatch.matching;

import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.AvailabilitySlotStatus;
import com.pulsaride.dispatch.domain.DispatchRequest;
import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.AvailabilitySlotRepository;
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
    private final AvailabilitySlotRepository slotRepository;
    private final ProfessionalRepository professionalRepository;
    private final AssignmentRepository assignmentRepository;

    public MatchingService(
            AvailabilitySlotRepository slotRepository,
            ProfessionalRepository professionalRepository,
            AssignmentRepository assignmentRepository
    ) {
        this.slotRepository = slotRepository;
        this.professionalRepository = professionalRepository;
        this.assignmentRepository = assignmentRepository;
    }

    public Optional<Professional> select(DispatchRequest request, DispatchStrategy strategy) {
        return selectSlot(request, strategy).map(AvailabilitySlot::getProfessional);
    }

    public Optional<AvailabilitySlot> selectSlot(DispatchRequest request, DispatchStrategy strategy) {
        ensureSlotsForExistingProfessionals();
        List<AvailabilitySlot> available = slotRepository
                .findByStatusOrderByProfessionalLoadAscProfessionalExperienceYearsDesc(AvailabilitySlotStatus.AVAILABLE);

        return switch (strategy) {
            case S1 -> roundRobin(available);
            case S2 -> available.stream()
                    .filter(slot -> slot.getSpecialtyTag().equalsIgnoreCase(request.getSpecialtyHint()))
                    .findFirst();
            case S3 -> available.stream()
                    .max(Comparator.comparingDouble(slot -> classicScore(request, slot.getProfessional())));
            case S4 -> available.stream()
                    .max(Comparator.comparingDouble(slot -> aiScore(request, slot.getProfessional())));
        };
    }

    private Optional<AvailabilitySlot> roundRobin(List<AvailabilitySlot> available) {
        List<AvailabilitySlot> ordered = available.stream()
                .sorted(Comparator.comparing(slot -> slot.getProfessional().getId()))
                .toList();
        if (ordered.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> lastProfessionalId = assignmentRepository
                .findFirstByStrategyOrderByProposedAtDescIdDesc(DispatchStrategy.S1)
                .map(assignment -> assignment.getProfessional().getId());
        if (lastProfessionalId.isEmpty()) {
            return Optional.of(ordered.getFirst());
        }

        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).getProfessional().getId().equals(lastProfessionalId.get())) {
                return Optional.of(ordered.get((index + 1) % ordered.size()));
            }
        }
        return Optional.of(ordered.getFirst());
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

    private void ensureSlotsForExistingProfessionals() {
        professionalRepository.findAll().stream()
                .filter(professional -> slotRepository.findByProfessionalId(professional.getId()).isEmpty())
                .forEach(professional -> {
                    AvailabilitySlot slot = new AvailabilitySlot();
                    slot.setId("slot_" + professional.getId());
                    slot.setProfessional(professional);
                    slot.setSpecialtyTag(professional.getSpecialtyTag());
                    slot.setStatus(switch (professional.getStatus()) {
                        case AVAILABLE -> AvailabilitySlotStatus.AVAILABLE;
                        case PROPOSED -> AvailabilitySlotStatus.RESERVED;
                        case BUSY -> AvailabilitySlotStatus.BUSY;
                        case BREAK -> AvailabilitySlotStatus.BREAK;
                        case OFFLINE -> AvailabilitySlotStatus.OFFLINE;
                    });
                    slotRepository.save(slot);
                });
    }
}

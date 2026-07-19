package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.AvailabilitySlot;
import com.pulsaride.dispatch.domain.AvailabilitySlotStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, String> {
    List<AvailabilitySlot> findByStatusOrderByProfessionalLoadAscProfessionalExperienceYearsDesc(
            AvailabilitySlotStatus status
    );

    List<AvailabilitySlot> findByStatusAndSpecialtyTagIgnoreCaseOrderByProfessionalLoadAscProfessionalExperienceYearsDesc(
            AvailabilitySlotStatus status,
            String specialtyTag
    );

    Optional<AvailabilitySlot> findByProfessionalId(String professionalId);
}

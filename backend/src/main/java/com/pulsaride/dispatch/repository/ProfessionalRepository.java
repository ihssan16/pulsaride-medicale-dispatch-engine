package com.pulsaride.dispatch.repository;

import com.pulsaride.dispatch.domain.Professional;
import com.pulsaride.dispatch.domain.ProfessionalStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfessionalRepository extends JpaRepository<Professional, String> {
    List<Professional> findByStatusOrderByLoadAscExperienceYearsDesc(ProfessionalStatus status);
    List<Professional> findByStatusAndSpecialtyTagOrderByLoadAscExperienceYearsDesc(
            ProfessionalStatus status,
            String specialtyTag
    );
}

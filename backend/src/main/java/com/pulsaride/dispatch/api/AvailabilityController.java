package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.service.AvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AvailabilityController {
    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    @GetMapping({"/availability", "/api/availability"})
    public AvailabilitySummaryResponse summary() {
        return service.summary();
    }

    @GetMapping({"/availability/specialties/{specialtyTag}", "/api/availability/specialties/{specialtyTag}"})
    public SpecialtyAvailabilityResponse specialty(@PathVariable String specialtyTag) {
        return service.specialty(specialtyTag);
    }
}

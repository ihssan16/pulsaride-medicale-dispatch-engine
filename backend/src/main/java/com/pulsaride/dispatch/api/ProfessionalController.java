package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.ProfessionalStatus;
import com.pulsaride.dispatch.repository.ProfessionalRepository;
import com.pulsaride.dispatch.service.ProfessionalService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/professionals")
public class ProfessionalController {
    private final ProfessionalRepository repository;
    private final ProfessionalService service;

    public ProfessionalController(ProfessionalRepository repository, ProfessionalService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public List<ProfessionalResponse> list(@RequestParam(required = false) ProfessionalStatus status) {
        var professionals = status == null
                ? repository.findAll()
                : repository.findByStatusOrderByLoadAscExperienceYearsDesc(status);
        return professionals.stream().map(ProfessionalResponse::from).toList();
    }

    @PostMapping
    public ProfessionalResponse create(@Valid @RequestBody CreateProfessionalRequest command) {
        return ProfessionalResponse.from(service.create(command));
    }

    @PutMapping("/{id}/status")
    public ProfessionalResponse updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateProfessionalStatusRequest command
    ) {
        return ProfessionalResponse.from(service.updateStatus(id, command.status()));
    }
}

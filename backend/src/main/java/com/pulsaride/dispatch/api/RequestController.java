package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.repository.AssignmentRepository;
import com.pulsaride.dispatch.repository.StateTransitionRepository;
import com.pulsaride.dispatch.service.DispatchService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/requests")
public class RequestController {
    private final DispatchRequestRepository repository;
    private final AssignmentRepository assignmentRepository;
    private final StateTransitionRepository transitionRepository;
    private final DispatchService service;

    public RequestController(
            DispatchRequestRepository repository,
            AssignmentRepository assignmentRepository,
            StateTransitionRepository transitionRepository,
            DispatchService service
    ) {
        this.repository = repository;
        this.assignmentRepository = assignmentRepository;
        this.transitionRepository = transitionRepository;
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DispatchRequestResponse create(@Valid @RequestBody CreateDispatchRequest command) {
        return DispatchRequestResponse.from(service.create(command));
    }

    @GetMapping("/{id}")
    public DispatchRequestResponse get(@PathVariable String id) {
        return repository.findById(id)
                .map(DispatchRequestResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("Request not found: " + id));
    }

    @GetMapping("/{id}/assignments")
    public List<AssignmentResponse> assignments(@PathVariable String id) {
        ensureRequestExists(id);
        return assignmentRepository.findByRequestIdOrderByProposedAtDesc(id)
                .stream()
                .map(AssignmentResponse::from)
                .toList();
    }

    @GetMapping("/{id}/transitions")
    public List<StateTransitionResponse> transitions(@PathVariable String id) {
        ensureRequestExists(id);
        return transitionRepository.findByRequestIdOrderByOccurredAtAsc(id)
                .stream()
                .map(StateTransitionResponse::from)
                .toList();
    }

    private void ensureRequestExists(String id) {
        if (!repository.existsById(id)) {
            throw new EntityNotFoundException("Request not found: " + id);
        }
    }
}

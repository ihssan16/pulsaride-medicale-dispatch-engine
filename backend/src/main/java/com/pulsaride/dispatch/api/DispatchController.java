package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.domain.RequestStatus;
import com.pulsaride.dispatch.matching.DispatchStrategy;
import com.pulsaride.dispatch.repository.DispatchRequestRepository;
import com.pulsaride.dispatch.service.DispatchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch-requests")
public class DispatchController {
    private final DispatchRequestRepository repository;
    private final DispatchService service;

    public DispatchController(DispatchRequestRepository repository, DispatchService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public List<DispatchRequestResponse> list(@RequestParam(required = false) RequestStatus status) {
        var requests = status == null ? repository.findAll() : repository.findByStatusOrderByCreatedAtAsc(status);
        return requests.stream().map(DispatchRequestResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DispatchRequestResponse create(@Valid @RequestBody CreateDispatchRequest command) {
        return DispatchRequestResponse.from(service.createAndDispatch(command));
    }

    @PostMapping("/next")
    public DispatchRequestResponse dispatchNext(@RequestParam(defaultValue = "S3") DispatchStrategy strategy) {
        return DispatchRequestResponse.from(service.dispatchNext(strategy));
    }

    @PostMapping("/{id}/dispatch")
    public DispatchRequestResponse dispatch(
            @PathVariable String id,
            @RequestParam(defaultValue = "S3") DispatchStrategy strategy
    ) {
        return DispatchRequestResponse.from(service.dispatch(id, strategy));
    }

    @PostMapping("/{id}/accept")
    public DispatchRequestResponse accept(@PathVariable String id) {
        return DispatchRequestResponse.from(service.accept(id));
    }

    @PostMapping("/{id}/close")
    public DispatchRequestResponse close(@PathVariable String id) {
        return DispatchRequestResponse.from(service.close(id));
    }

    @PostMapping("/{id}/refuse")
    public DispatchRequestResponse refuse(@PathVariable String id) {
        return DispatchRequestResponse.from(service.refuse(id));
    }

    @PostMapping("/{id}/timeout")
    public DispatchRequestResponse timeout(@PathVariable String id) {
        return DispatchRequestResponse.from(service.timeout(id));
    }
}

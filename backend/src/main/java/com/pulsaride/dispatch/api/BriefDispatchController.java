package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.matching.DispatchStrategy;
import com.pulsaride.dispatch.service.DispatchService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dispatch")
public class BriefDispatchController {
    private final DispatchService service;

    public BriefDispatchController(DispatchService service) {
        this.service = service;
    }

    @PostMapping("/next")
    public DispatchRequestResponse dispatchNext(@RequestParam(defaultValue = "S1") DispatchStrategy strategy) {
        return DispatchRequestResponse.from(service.dispatchNext(strategy));
    }

    @PostMapping("/{requestId}")
    public DispatchRequestResponse dispatch(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "S1") DispatchStrategy strategy
    ) {
        return DispatchRequestResponse.from(service.dispatch(requestId, strategy));
    }

    @PostMapping("/{requestId}/accept")
    public DispatchRequestResponse accept(@PathVariable String requestId) {
        return DispatchRequestResponse.from(service.accept(requestId));
    }

    @PostMapping("/{requestId}/refuse")
    public DispatchRequestResponse refuse(@PathVariable String requestId) {
        return DispatchRequestResponse.from(service.refuse(requestId));
    }

    @PostMapping("/{requestId}/timeout")
    public DispatchRequestResponse timeout(@PathVariable String requestId) {
        return DispatchRequestResponse.from(service.timeout(requestId));
    }

    @PostMapping("/{requestId}/close")
    public DispatchRequestResponse close(@PathVariable String requestId) {
        return DispatchRequestResponse.from(service.close(requestId));
    }
}

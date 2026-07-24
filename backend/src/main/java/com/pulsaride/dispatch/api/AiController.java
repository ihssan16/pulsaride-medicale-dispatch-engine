package com.pulsaride.dispatch.api;

import com.pulsaride.dispatch.ai.AiTriageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiController {
    private final AiTriageService triageService;

    public AiController(AiTriageService triageService) {
        this.triageService = triageService;
    }

    @PostMapping("/triage")
    public TriageResponse triage(@Valid @RequestBody TriageRequest request) {
        return triageService.triage(request.text());
    }
}

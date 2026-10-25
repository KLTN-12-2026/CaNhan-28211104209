package com.carrental.modules.ai.itinerary.controller;

import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryPreviewResponse;
import com.carrental.modules.ai.itinerary.service.ItineraryPreviewService;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/itineraries/preview")
public class ItineraryPreviewController {
    private final ItineraryPreviewService service;

    public ItineraryPreviewController(ItineraryPreviewService service) {
        this.service = service;
    }

    @PostMapping
    public ItineraryPreviewResponse preview(
            @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody ItineraryPreviewRequest request) {
        return service.preview(actor, request);
    }
}

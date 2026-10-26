package com.carrental.modules.ai.itinerary.controller;

import com.carrental.modules.ai.itinerary.dto.CreateItineraryRequest;
import com.carrental.modules.ai.itinerary.dto.ItineraryDetailDto;
import com.carrental.modules.ai.itinerary.dto.ItinerarySummaryDto;
import com.carrental.modules.ai.itinerary.service.ItineraryStorageService;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/itineraries")
public class CustomerItineraryController {
    private final ItineraryStorageService storageService;

    public CustomerItineraryController(ItineraryStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItineraryDetailDto create(
            @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody CreateItineraryRequest request) {
        return storageService.create(actor, request);
    }

    @GetMapping
    public List<ItinerarySummaryDto> list(@AuthenticationPrincipal Actor actor) {
        return storageService.list(actor);
    }

    @GetMapping("/{id}")
    public ItineraryDetailDto get(
            @AuthenticationPrincipal Actor actor,
            @PathVariable String id) {
        return storageService.get(actor, id);
    }
}

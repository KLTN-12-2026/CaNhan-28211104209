package com.carrental.modules.vehicle.controller;

import com.carrental.modules.vehicle.service.VehicleAmenityService;
import com.carrental.security.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class VehicleAmenityController {
    private final VehicleAmenityService service;

    public VehicleAmenityController(VehicleAmenityService service) {
        this.service = service;
    }

    @GetMapping("/vehicles/{id}/amenities")
    public VehicleAmenityService.AmenityLinksDto get(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        return service.getAmenities(id);
    }

    @PutMapping("/vehicles/{id}/amenities")
    public VehicleAmenityService.AmenityLinksDto put(@AuthenticationPrincipal Actor actor,
                                                     @PathVariable String id,
                                                     @RequestBody UpdateRequest request) {
        return service.updateAmenities(actor, id, request.amenityIds(), request.expectedRevision());
    }

    public record UpdateRequest(List<String> amenityIds, String expectedRevision) {}
}
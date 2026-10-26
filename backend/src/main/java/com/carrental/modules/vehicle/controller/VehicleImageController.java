package com.carrental.modules.vehicle.controller;

import com.carrental.modules.vehicle.service.VehicleImageService;
import com.carrental.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class VehicleImageController {
    private final VehicleImageService service;

    public VehicleImageController(VehicleImageService service) {
        this.service = service;
    }

    @GetMapping("/vehicles/{vehicleId}/images")
    public VehicleImageService.ImagesDto get(@AuthenticationPrincipal Actor actor, @PathVariable String vehicleId) {
        return service.getImages(vehicleId);
    }

    @PostMapping("/vehicles/{vehicleId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleImageService.ImagesDto create(@AuthenticationPrincipal Actor actor,
                                                @PathVariable String vehicleId,
                                                @RequestBody VehicleImageService.CreateRequest request) {
        return service.createImage(actor, vehicleId, request);
    }

    @PutMapping("/vehicles/{vehicleId}/images/{imageId}")
    public VehicleImageService.ImagesDto update(@AuthenticationPrincipal Actor actor,
                                                @PathVariable String vehicleId,
                                                @PathVariable String imageId,
                                                @RequestBody VehicleImageService.UpdateRequest request) {
        return service.updateImage(actor, vehicleId, imageId, request);
    }

    @PutMapping("/vehicles/{vehicleId}/images/order")
    public VehicleImageService.ImagesDto reorder(@AuthenticationPrincipal Actor actor,
                                                 @PathVariable String vehicleId,
                                                 @RequestBody VehicleImageService.ReorderRequest request) {
        return service.reorderImages(actor, vehicleId, request);
    }
}
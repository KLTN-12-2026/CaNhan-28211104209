package com.carrental.modules.vehicle.controller;

import com.carrental.modules.vehicle.service.VehicleCatalogService;
import com.carrental.security.Actor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/{kind:vehicle-brands|vehicle-categories|amenities}")
public class VehicleCatalogController {
    private final VehicleCatalogService service;
    public VehicleCatalogController(VehicleCatalogService service) { this.service = service; }
    public record Create(String name) {}
    public record Update(String name, String expectedRevision) {}
    @GetMapping
    public List<VehicleCatalogService.Item> list(@AuthenticationPrincipal Actor actor, @PathVariable String kind) {
        return service.list(actor, kind);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public VehicleCatalogService.Item create(@AuthenticationPrincipal Actor actor, @PathVariable String kind, @RequestBody Create input) {
        return service.create(actor, kind, input.name());
    }
    @PutMapping("/{id}")
    public VehicleCatalogService.Item update(@AuthenticationPrincipal Actor actor, @PathVariable String kind, @PathVariable String id, @RequestBody Update input) {
        return service.update(actor, kind, id, input.name(), input.expectedRevision());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Actor actor, @PathVariable String kind, @PathVariable String id,
                       @RequestParam(required=false) String expectedRevision) {
        service.delete(actor, kind, id, expectedRevision);
    }
}

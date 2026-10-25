package com.carrental.modules.location.controller;

import com.carrental.modules.location.service.LocationService;
import com.carrental.modules.location.service.VisitMetadata;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/locations")
public class LocationController {
    private final LocationService locations;

    public LocationController(LocationService locations) {
        this.locations = locations;
    }

    public record Input(
        @NotBlank String categoryId,
        @NotBlank @Size(max = 200) String name,
        @NotNull @Digits(integer = 3, fraction = 7) @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @Digits(integer = 3, fraction = 7) @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        VisitMetadata metadata
    ) {}

    public record UpdateInput(
        @NotBlank String categoryId,
        @NotBlank @Size(max = 200) String name,
        @NotNull @Digits(integer = 3, fraction = 7) @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @Digits(integer = 3, fraction = 7) @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedRevision,
        VisitMetadata metadata
    ) {
        LocationService.Input toInput() {
            return new LocationService.Input(categoryId, name, latitude, longitude, status, metadata);
        }
    }

    @GetMapping
    public List<LocationService.View> list(@AuthenticationPrincipal Actor actor) {
        return locations.list(actor);
    }

    @GetMapping("/{id}")
    public LocationService.View get(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
        return locations.get(actor, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationService.View create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Input input) {
        return locations.create(actor, new LocationService.Input(
            input.categoryId(), input.name(), input.latitude(), input.longitude(), input.status(), input.metadata()
        ));
    }

    @PutMapping("/{id}")
    public LocationService.View update(
        @AuthenticationPrincipal Actor actor,
        @PathVariable String id,
        @Valid @RequestBody UpdateInput input
    ) {
        return locations.update(actor, id, input.toInput(), input.expectedRevision());
    }
}

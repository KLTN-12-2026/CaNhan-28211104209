package com.carrental.modules.location.controller;

import com.carrental.modules.location.service.LocationCategoryService;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/admin/location-categories")
public class LocationCategoryController {
    private final LocationCategoryService categories;

    public LocationCategoryController(LocationCategoryService categories) {
        this.categories = categories;
    }

    public record Input(@NotBlank @Size(max = 100) String name) {}

    public record UpdateInput(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedRevision
    ) {}

    @GetMapping
    public List<LocationCategoryService.View> list(@AuthenticationPrincipal Actor actor) {
        return categories.list(actor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationCategoryService.View create(
        @AuthenticationPrincipal Actor actor,
        @Valid @RequestBody Input input
    ) {
        return categories.create(actor, input.name());
    }

    @PutMapping("/{id}")
    public LocationCategoryService.View update(
        @AuthenticationPrincipal Actor actor,
        @PathVariable String id,
        @Valid @RequestBody UpdateInput input
    ) {
        return categories.update(actor, id, input.name(), input.expectedRevision());
    }
}
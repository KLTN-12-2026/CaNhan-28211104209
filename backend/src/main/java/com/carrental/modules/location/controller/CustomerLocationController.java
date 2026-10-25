package com.carrental.modules.location.controller;

import com.carrental.modules.location.service.LocationService;
import com.carrental.security.Actor;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/locations")
public class CustomerLocationController {
    private final LocationService locations;
    public CustomerLocationController(LocationService locations){this.locations=locations;}
    @GetMapping public List<LocationService.PublicView> list(@AuthenticationPrincipal Actor actor){
        return locations.activeCatalog(actor);
    }
}

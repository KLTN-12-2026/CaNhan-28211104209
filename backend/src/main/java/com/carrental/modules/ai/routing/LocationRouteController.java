package com.carrental.modules.ai.routing;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/routes/preview")
public class LocationRouteController {
    public record Request(@NotNull @Size(min=2,max=8) List<@NotBlank @Size(max=36) String> locationIds) {}
    private final LocationRouteService service;
    public LocationRouteController(LocationRouteService service){this.service=service;}
    @PostMapping public OsrmRoutingClient.Estimate preview(@AuthenticationPrincipal Actor actor,@Valid @RequestBody Request request){
        return service.preview(actor,request.locationIds());
    }
}

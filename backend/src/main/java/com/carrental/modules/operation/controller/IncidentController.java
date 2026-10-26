package com.carrental.modules.operation.controller;
import com.carrental.modules.operation.service.IncidentService;
import com.carrental.security.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController @RequestMapping("/api/v1/incidents") public class IncidentController {
 private final IncidentService service;public IncidentController(IncidentService s){service=s;}
 @GetMapping public Object list(@AuthenticationPrincipal Actor a,@RequestParam String bookingId){return service.list(a,bookingId);}
 @PostMapping public Object create(@AuthenticationPrincipal Actor a,@RequestBody IncidentService.Input i){return service.create(a,i);}
 @PostMapping("/{id}/actions") public Object update(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody IncidentService.Update i){return service.update(a,id,i);}
 @GetMapping("/{id}/events") public Object events(@AuthenticationPrincipal Actor a,@PathVariable String id){return service.events(a,id);}
}

package com.carrental.modules.vehicle.controller;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.security.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController @RequestMapping("/api/v1") public class VehicleController {
 private final VehicleService service;public VehicleController(VehicleService s){service=s;}
 public record Operation(String status,long version){}
 @GetMapping("/vehicles") public Object list(){return service.list();}
 @GetMapping("/vehicles/catalog") public Object catalog(){return service.catalog();}
 @GetMapping("/vehicles/{id}") public Object detail(@PathVariable String id){return service.view(service.get(id));}
 @PostMapping("/admin/vehicles") public Object create(@AuthenticationPrincipal Actor a,@RequestBody VehicleService.View r){return service.create(a,r);}
 @PutMapping("/admin/vehicles/{id}") public Object edit(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody VehicleService.View r){return service.edit(a,id,r);}
 @DeleteMapping("/admin/vehicles/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 public void delete(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestParam(required=false) Long version){
  a.require("ADMIN");
  if(version==null)throw com.carrental.exception.DomainException.bad("Phiên bản xe là bắt buộc.");
  service.delete(a,id,version);
 }
 @PatchMapping("/vehicles/{id}/operation") public Object operation(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Operation r){return service.operation(a,id,r.status(),r.version());}
}

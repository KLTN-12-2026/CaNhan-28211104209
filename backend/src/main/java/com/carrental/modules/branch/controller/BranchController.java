package com.carrental.modules.branch.controller;
import com.carrental.modules.branch.service.BranchService;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.List;
@RestController @RequestMapping("/api/v1") public class BranchController {
 private final BranchService service;public BranchController(BranchService s){service=s;}
 public record BranchInput(@NotBlank @Size(max=120)String name,@NotBlank @Size(max=300)String address,long version){}
 public record StatusInput(@NotBlank @Pattern(regexp="ACTIVE|INACTIVE") String status,@NotNull @PositiveOrZero Long expectedVersion){}
 public record RouteInput(@NotBlank String pickupBranchId,@NotBlank String returnBranchId,@Min(0) @Max(100000000)long fee,boolean enabled,@NotNull @PositiveOrZero Long expectedVersion){}
 public record Assignment(@NotBlank String userId,@NotBlank String branchId){}
 @GetMapping("/branches") public Object branches(){return service.list();}
 @GetMapping("/branch-routes") public Object routes(){return service.routes();}
 @GetMapping("/me/branches") public Object assignments(@AuthenticationPrincipal Actor a){return service.assignments(a);}
 @GetMapping("/admin/staff-branches") public List<BranchService.StaffBranchView> staffBranches(@AuthenticationPrincipal Actor a){return service.allStaffBranches(a);}
 @PostMapping("/admin/branches") public Object create(@AuthenticationPrincipal Actor a,@Valid @RequestBody BranchInput i){return service.save(a,null,i.name(),i.address(),0);}
 @PutMapping("/admin/branches/{id}") public Object edit(@AuthenticationPrincipal Actor a,@PathVariable String id,@Valid @RequestBody BranchInput i){return service.save(a,id,i.name(),i.address(),i.version());}
 @DeleteMapping("/admin/branches/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 public void delete(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestParam(required=false) Long expectedVersion){
  a.require("ADMIN");if(expectedVersion==null)throw com.carrental.exception.DomainException.bad("Phiên bản chi nhánh là bắt buộc.");service.delete(a,id,expectedVersion);
 }
 @PutMapping("/admin/branches/{id}/status") public BranchService.BranchView status(@AuthenticationPrincipal Actor a,@PathVariable String id,@Valid @RequestBody StatusInput i){return service.changeStatus(a,id,i.status(),i.expectedVersion());}
 @PutMapping("/admin/branch-routes") public void route(@AuthenticationPrincipal Actor a,@Valid @RequestBody RouteInput i){service.route(a,i.pickupBranchId(),i.returnBranchId(),i.fee(),i.enabled(),i.expectedVersion());}
 @PostMapping("/admin/staff-branches") public void assign(@AuthenticationPrincipal Actor a,@Valid @RequestBody Assignment i){service.assign(a,i.userId(),i.branchId());}
}

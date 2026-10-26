package com.carrental.modules.user.controller;
import com.carrental.modules.user.service.*;
import com.carrental.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.carrental.security.AuthRequestLimiter;
import jakarta.servlet.http.HttpServletRequest;
@RestController @RequestMapping("/api/v1")
public class UserController {
 private final AccountService accounts;private final LicenseService licenses;private final AuthRequestLimiter limiter;
 public UserController(AccountService a,LicenseService l,AuthRequestLimiter limiter){accounts=a;licenses=l;this.limiter=limiter;}
 public record Profile(@NotBlank @Size(max=100)String fullName,@Size(max=20)String phone,long version){}
 public record Password(@NotBlank @Size(max=72)String current,@NotBlank @Size(min=8,max=72)String next){}
 public record Staff(@NotBlank @Email @Size(max=254)String email,@NotBlank @Size(max=100)String fullName,@Size(min=8,max=72)@NotBlank String password){}
 public record Status(@NotBlank String status,@NotNull @PositiveOrZero Long version){}
 @PutMapping("/me/profile") public Object profile(@AuthenticationPrincipal Actor a,@Valid @RequestBody Profile r){return accounts.update(a,r.fullName(),r.phone(),r.version());}
 @PutMapping("/me/password") public void password(@AuthenticationPrincipal Actor a,@Valid @RequestBody Password r,HttpServletRequest request){limiter.check("password-ip",request.getRemoteAddr(),15);limiter.check("password-account",a.id(),5);accounts.password(a,r.current(),r.next());}
 @GetMapping("/me/license") public Object license(@AuthenticationPrincipal Actor a){return licenses.mine(a);}
 @PutMapping("/me/license") public Object license(@AuthenticationPrincipal Actor a,@RequestBody LicenseService.View r){return licenses.save(a,r);}
 @GetMapping("/admin/accounts") public Object accounts(@AuthenticationPrincipal Actor a){return accounts.list(a);}
 @PostMapping("/admin/accounts") public Object staff(@AuthenticationPrincipal Actor a,@Valid @RequestBody Staff r){return accounts.staff(a,r.email(),r.fullName(),r.password());}
 @PatchMapping("/admin/accounts/{id}") public Object status(@AuthenticationPrincipal Actor a,@PathVariable String id,@Valid @RequestBody Status r){return accounts.status(a,id,r.status(),r.version());}
}

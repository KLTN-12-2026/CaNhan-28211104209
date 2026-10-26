package com.carrental.modules.user.service;
import com.carrental.modules.user.entity.Account;
import com.carrental.modules.user.repository.AccountRepository;
import com.carrental.exception.DomainException;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
@Service
public class AccountService {
 private final AccountRepository repo; private final PasswordEncoder passwords;
 public AccountService(AccountRepository repo,PasswordEncoder passwords){this.repo=repo;this.passwords=passwords;}
 public record Profile(String id,String fullName,String email,String phone,String role,String status,long version){}
 public Profile view(Account a){return new Profile(a.id,a.fullName,a.email,a.phone,a.role,a.status,a.version);}
 public Profile profile(String id){return view(require(id));}
 public Account require(String id){return repo.findById(id).orElseThrow(DomainException::missing);}
 public void requireStaff(String userId){
  Account a=repo.findById(userId).orElseThrow(()->DomainException.bad("Tài khoản phải là Staff."));
  if(!"STAFF".equals(a.role))throw DomainException.bad("Tài khoản phải là Staff.");
 }
 public Actor activeActor(String id,long version){
  Account a=require(id);
  if(!"ACTIVE".equals(a.status)||a.tokenVersion!=version)throw new DomainException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Phiên không còn hiệu lực.");
  return new Actor(a.id,a.role,a.fullName);
 }
 @Transactional public Account register(String email,String name,String password){
  com.carrental.security.PasswordPolicy.validate(password);
  String normalized=email.strip().toLowerCase(Locale.ROOT);
  if(repo.findByEmail(normalized).isPresent())throw DomainException.conflict("Email đã được sử dụng.");
  Account a=new Account();a.email=normalized;a.fullName=name.strip();a.passwordHash=passwords.encode(password);a.role="USER";a.status="ACTIVE";return repo.saveAndFlush(a);
 }
 public Account authenticate(String email,String password){
  Account a=repo.findByEmail(email.strip().toLowerCase(Locale.ROOT)).orElse(null);
  if(a==null||!"ACTIVE".equals(a.status)||!passwords.matches(password,a.passwordHash))throw new DomainException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Email hoặc mật khẩu không đúng.");
  return a;
 }
 @Transactional public void revoke(String id){Account a=repo.lock(id).orElseThrow(DomainException::missing);a.tokenVersion++;}
 @Transactional public Profile update(Actor actor,String name,String phone,long version){
  Account a=repo.lock(actor.id()).orElseThrow(DomainException::missing);
  if(a.version!=version)throw DomainException.conflict("Hồ sơ đã thay đổi.");
  String normalizedPhone=phone==null||phone.isBlank()?null:phone.strip().replaceAll("[\\s-]","");
  if(normalizedPhone!=null&&!normalizedPhone.matches("\\+?[0-9]{7,15}"))throw DomainException.bad("Số điện thoại cần 7–15 chữ số, có thể bắt đầu bằng +.");
  a.fullName=name.strip();a.phone=normalizedPhone;repo.flush();return view(a);
 }
 @Transactional public void password(Actor actor,String current,String next){
  com.carrental.security.PasswordPolicy.validate(next);
  Account a=repo.lock(actor.id()).orElseThrow(DomainException::missing);
  if(!passwords.matches(current,a.passwordHash))throw DomainException.bad("Mật khẩu hiện tại không đúng.");
  if(passwords.matches(next,a.passwordHash))throw DomainException.bad("Mật khẩu mới phải khác mật khẩu cũ.");
  a.passwordHash=passwords.encode(next);a.tokenVersion++;
 }
 public List<Profile> list(Actor actor){actor.require("ADMIN");return repo.findAll().stream().map(this::view).toList();}
 @Transactional public Profile staff(Actor actor,String email,String name,String password){
  actor.require("ADMIN");Account a=register(email,name,password);a.role="STAFF";repo.flush();return view(a);
 }
 @Transactional public Profile status(Actor actor,String id,String status,Long version){
  actor.require("ADMIN");
  if(version==null||version<0)throw DomainException.bad("expectedVersion không hợp lệ.");
  Account a=repo.lock(id).orElseThrow(DomainException::missing);
  if("ADMIN".equals(a.role))throw DomainException.forbidden();
  if(a.version!=version)throw DomainException.conflict("Tài khoản đã thay đổi.");
  if(!Set.of("ACTIVE","LOCKED").contains(status))throw DomainException.bad("Trạng thái không hợp lệ.");
  a.status=status;a.tokenVersion++;repo.flush();return view(a);
 }
}

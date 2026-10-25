package com.carrental.modules.branch.service;
import com.carrental.modules.branch.entity.*;
import com.carrental.modules.branch.repository.*;
import com.carrental.modules.user.service.AccountService;
import com.carrental.exception.DomainException;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service public class BranchService {
 private final BranchRepository branches;private final BranchRouteRepository routes;private final JdbcTemplate jdbc;private final AccountService accounts;
 public BranchService(BranchRepository b,BranchRouteRepository r,JdbcTemplate j,AccountService a){branches=b;routes=r;jdbc=j;accounts=a;}
 public record BranchView(String id,String name,String address,String status,long version){}
 public record RouteView(String id,String pickupBranchId,String returnBranchId,long fee,boolean enabled,long version){}
 public List<BranchView> list(){return branches.findAll().stream().map(b->new BranchView(b.id,b.name,b.address,b.status,b.version)).toList();}
 public List<RouteView> routes(){return routes.findAll().stream().map(r->new RouteView(r.id,r.pickupBranchId,r.returnBranchId,r.fee,r.enabled,r.version)).toList();}
 public Branch active(String id){var b=branches.findById(id).orElseThrow(DomainException::missing);if(!"ACTIVE".equals(b.status))throw DomainException.conflict("Chi nhánh không hoạt động.");return b;}
 public long fee(String pickup,String destination){
  active(pickup);active(destination);
  var route=routes.findByPickupBranchIdAndReturnBranchId(pickup,destination).orElseThrow(()->DomainException.bad("Tuyến nhận/trả chưa được doanh nghiệp cho phép."));
  if(!route.enabled)throw DomainException.conflict("Tuyến nhận/trả đã ngừng phục vụ.");return route.fee;
 }
 public record BookingRoute(String pickupName,String returnName,long fee){}
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
 public BookingRoute bookingRoute(String pickup,String destination){
  if(pickup==null||pickup.isBlank()||destination==null||destination.isBlank())throw DomainException.bad("Cần chọn chi nhánh nhận và trả.");
  // Booking's outer transaction retains these ordered row locks until quote/create commits.
  var locked=new HashMap<String,Branch>();
  for(var id:java.util.stream.Stream.of(pickup,destination).distinct().sorted().toList()){
   var b=branches.lock(id).orElseThrow(DomainException::missing);
   if(!"ACTIVE".equals(b.status))throw DomainException.conflict("Chi nhánh không hoạt động.");
   locked.put(id,b);
  }
  return new BookingRoute(locked.get(pickup).name,locked.get(destination).name,fee(pickup,destination));
 }
 public boolean assigned(Actor actor,String branch){return actor.is("STAFF")&&jdbc.queryForObject("select count(*) from staff_branches where user_id=? and branch_id=?",Long.class,actor.id(),branch)>0;}
 public void requireStaff(Actor actor,String branch){if(!assigned(actor,branch))throw DomainException.forbidden();}
 @Transactional public BranchView save(Actor actor,String id,String name,String address,long version){
  actor.require("ADMIN");Branch b=id==null?new Branch():branches.lock(id).orElseThrow(DomainException::missing);
  if(id!=null&&b.version!=version)throw DomainException.conflict("Chi nhánh đã thay đổi.");
  b.name=name.strip();b.address=address.strip();if(id==null)b.status="ACTIVE";branches.saveAndFlush(b);return new BranchView(b.id,b.name,b.address,b.status,b.version);
 }
 @Transactional public BranchView changeStatus(Actor actor,String id,String status,long expectedVersion){
  actor.require("ADMIN");
  if((!"ACTIVE".equals(status)&&!"INACTIVE".equals(status))||expectedVersion<0)throw DomainException.bad("Trạng thái phải là ACTIVE/INACTIVE và expectedVersion không âm.");
  var b=branches.lock(id).orElseThrow(DomainException::missing);
  if(b.version!=expectedVersion)throw DomainException.conflict("Chi nhánh đã thay đổi. Vui lòng tải lại trước khi lưu.");
  b.status=status;branches.saveAndFlush(b);return new BranchView(b.id,b.name,b.address,b.status,b.version);
 }
 @Transactional public void delete(Actor actor,String id,long expectedVersion){
  actor.require("ADMIN");
  if(expectedVersion<0)throw DomainException.bad("Phiên bản chi nhánh phải là số không âm.");
  var branch=branches.lock(id).orElseThrow(DomainException::missing);
  if(branch.version!=expectedVersion)throw DomainException.conflict("Chi nhánh đã thay đổi. Vui lòng tải lại trước khi xóa.");
  try{branches.delete(branch);branches.flush();}
  catch(org.springframework.dao.DataIntegrityViolationException failure){
   throw DomainException.conflict("Chi nhánh còn được xe, đơn thuê, tuyến hoặc phân công nhân viên sử dụng nên không thể xóa. Hãy ngừng nhận đơn mới.");
  }
 }
@Transactional public void route(Actor actor,String pickup,String destination,long fee,boolean enabled,long expectedVersion){
  actor.require("ADMIN");
  if(expectedVersion<0)throw DomainException.bad("expectedVersion phải là số không âm; dùng 0 để tạo tuyến mới.");
  active(pickup);active(destination);
  var opt=routes.findByPickupBranchIdAndReturnBranchId(pickup,destination);
  if(opt.isEmpty()){
   if(expectedVersion!=0L)throw DomainException.conflict("Tuyến nhận/trả đã tồn tại ở version khác. Vui lòng tải lại.");
   var created=new BranchRoute();
   created.pickupBranchId=pickup;created.returnBranchId=destination;created.fee=fee;created.enabled=enabled;
   routes.saveAndFlush(created);return;
  }
  var r=opt.get();
  if(r.version!=expectedVersion)throw DomainException.conflict("Tuyến nhận/trả đã thay đổi.");
  r.fee=fee;r.enabled=enabled;
  routes.saveAndFlush(r);
 }
 @Transactional public void assign(Actor actor,String userId,String branch){
  actor.require("ADMIN");active(branch);
  accounts.requireStaff(userId);
  if(jdbc.queryForObject("select count(*) from staff_branches where user_id=? and branch_id=?",Long.class,userId,branch)==0)
   jdbc.update("insert into staff_branches(user_id,branch_id) values (?,?)",userId,branch);
 }
 public List<String> assignments(Actor actor){return jdbc.queryForList("select branch_id from staff_branches where user_id=?",String.class,actor.id());}
 public record StaffBranchView(String userId,String branchId){}
 public List<StaffBranchView> allStaffBranches(Actor actor){
  actor.require("ADMIN");
  return jdbc.query("select sb.user_id, sb.branch_id from staff_branches sb join users u on u.id=sb.user_id where u.role='STAFF' order by sb.user_id, sb.branch_id",
   (rs, rowNum) -> new StaffBranchView(rs.getString("user_id"), rs.getString("branch_id")));
 }
}

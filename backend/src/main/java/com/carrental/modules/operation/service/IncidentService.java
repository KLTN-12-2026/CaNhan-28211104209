package com.carrental.modules.operation.service;
import com.carrental.modules.operation.entity.IncidentCase;
import com.carrental.modules.operation.repository.IncidentCaseRepository;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.modules.vehicle.service.VehicleService;
import com.carrental.modules.branch.service.BranchService;
import com.carrental.exception.DomainException;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
@Service public class IncidentService {
 private final IncidentCaseRepository repo;private final BookingService bookings;private final VehicleService vehicles;private final BranchService branches;private final JdbcTemplate jdbc;private final Clock clock;
 public IncidentService(IncidentCaseRepository r,BookingService b,VehicleService v,BranchService br,JdbcTemplate j,Clock c){repo=r;bookings=b;vehicles=v;branches=br;jdbc=j;clock=c;}
 public record Input(String bookingId,String kind,Instant occurredAt,String description,String evidenceReference,boolean blockVehicle){}
 public record Update(String next,long version,long assessedDamage,long thirdPartyCoverage,long requestedAmount,String responsibilityBasis,String note){}
 public record View(String id,String bookingId,String kind,Instant occurredAt,Instant reportedAt,String description,String evidenceReference,String status,long assessedDamage,long thirdPartyCoverage,long requestedAmount,String responsibilityBasis,long version){}
 private View view(IncidentCase c){return new View(c.id,c.bookingId,c.kind,c.occurredAt,c.reportedAt,c.description,c.evidenceReference,c.status,c.assessedDamage,c.thirdPartyCoverage,c.requestedAmount,c.responsibilityBasis,c.version);}
 public List<View> list(Actor a,String bookingId){bookings.require(a,bookingId);return repo.findAll().stream().filter(c->c.bookingId.equals(bookingId)).map(this::view).toList();}
 @Transactional public View create(Actor a,Input i){
  var b=bookings.require(a,i.bookingId());
  if(!Set.of("IN_PROGRESS","RETURNED","COMPLETED").contains(b.status))throw DomainException.bad("Chỉ ghi nhận sự cố của chuyến đã giao xe.");
  var handoverRows=jdbc.queryForList("select kind,occurred_at from vehicle_handover_records where booking_id=?",b.id);
  var handoverRecord=handoverRows.stream().filter(r->"HANDOVER".equals(r.get("kind"))).findFirst();
  var returnRecord=handoverRows.stream().filter(r->"RETURN".equals(r.get("kind"))).findFirst();
  if(handoverRecord.isEmpty())throw DomainException.conflict("Chưa có biên bản bàn giao xe.");
  Instant custodyStart=((java.sql.Timestamp)handoverRecord.get().get("occurred_at")).toInstant();
  Instant custodyEnd=returnRecord.isPresent()?((java.sql.Timestamp)returnRecord.get().get("occurred_at")).toInstant():clock.instant();
  if(i.kind()==null||!Set.of("ACCIDENT","DAMAGE","VIOLATION").contains(i.kind())||i.occurredAt()==null||i.occurredAt().isAfter(clock.instant())||i.occurredAt().isBefore(custodyStart)||i.occurredAt().isAfter(custodyEnd))throw DomainException.bad("Thời điểm sự việc phải trong thời gian khách giữ xe thực tế.");
  text(i.description());if(i.evidenceReference()!=null&&i.evidenceReference().length()>2000)throw DomainException.bad("Tham chiếu tài liệu quá dài.");
  if(i.blockVehicle()){
   if(a.is("USER"))throw DomainException.forbidden();
   var veh=vehicles.lock(b.vehicleId);
   if(a.is("STAFF"))branches.requireStaff(a,veh.currentBranchId);
   veh.status="MAINTENANCE";
  }
  var c=new IncidentCase();c.bookingId=b.id;c.reportedBy=a.id();c.kind=i.kind();c.occurredAt=i.occurredAt();c.reportedAt=clock.instant();c.description=i.description().strip();c.evidenceReference=i.evidenceReference();c.status="OPEN";
  repo.saveAndFlush(c);event(c,a,null,"OPEN","Tiếp nhận hồ sơ; chưa kết luận trách nhiệm.");return view(c);
 }
 @Transactional public View update(Actor a,String id,Update i){
  var c=repo.lock(id).orElseThrow(DomainException::missing);bookings.require(a,c.bookingId);
  if(c.version!=i.version())throw DomainException.conflict("Hồ sơ đã thay đổi.");
  if("CLOSED".equals(c.status))throw DomainException.conflict("Hồ sơ đã đóng.");
  text(i.note());String next=i.next();if(next==null)throw DomainException.bad("Thiếu trạng thái.");
  if(a.is("USER")){
   if(!"DISPUTED".equals(next)||!"CLAIMED".equals(c.status))throw DomainException.forbidden();
  }else if("ASSESSED".equals(next)){
   if(!Set.of("OPEN","DISPUTED","ASSESSED").contains(c.status))throw DomainException.conflict("Cần giải quyết phản hồi trước khi đánh giá lại.");
   if(i.assessedDamage()<0||i.thirdPartyCoverage()<0||i.thirdPartyCoverage()>i.assessedDamage()||i.requestedAmount()<0||i.requestedAmount()>i.assessedDamage()-i.thirdPartyCoverage())throw DomainException.bad("Khoản yêu cầu phải trong thiệt hại còn lại sau chi trả bên khác.");
   text(i.responsibilityBasis());
   c.assessedDamage=i.assessedDamage();c.thirdPartyCoverage=i.thirdPartyCoverage();c.requestedAmount=i.requestedAmount();c.responsibilityBasis=i.responsibilityBasis().strip();
  }else if("CLAIMED".equals(next)){
   a.require("ADMIN");if(!"ASSESSED".equals(c.status))throw DomainException.conflict("Cần đánh giá có căn cứ trước khi gửi yêu cầu.");
  }else if("CLOSED".equals(next)){a.require("ADMIN");}
  else throw DomainException.bad("Thao tác không được hỗ trợ.");
  String previous=c.status;c.status=next;repo.flush();
  event(c,a,previous,next,i.note()+" | thiệt hại="+c.assessedDamage+"; bên khác="+c.thirdPartyCoverage+"; yêu cầu="+c.requestedAmount);
  return view(c);
 }
 private void text(String s){if(s==null||s.isBlank()||s.length()>1500)throw DomainException.bad("Cần nội dung có căn cứ, tối đa 1500 ký tự.");}
 private void event(IncidentCase c,Actor a,String previous,String action,String note){
  jdbc.update("insert into incident_events(id,case_id,actor_id,occurred_at,action,note,previous_status,next_status,assessed_damage,third_party_coverage,requested_amount,responsibility_basis) values(?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),c.id,a.id(),java.sql.Timestamp.from(clock.instant()),action,note,previous,c.status,c.assessedDamage,c.thirdPartyCoverage,c.requestedAmount,c.responsibilityBasis);
 }
 public Object events(Actor a,String id){var c=repo.findById(id).orElseThrow(DomainException::missing);bookings.require(a,c.bookingId);return jdbc.queryForList("select id,actor_id,occurred_at,action,note,previous_status,next_status,assessed_damage,third_party_coverage,requested_amount,responsibility_basis from incident_events where case_id=? order by occurred_at,id",id);}
}

package com.carrental.modules.booking.service;
import com.carrental.exception.DomainException;
import java.time.Instant;
import java.util.*;
public final class SchedulePolicy {
 public record Slot(String id,String pickup,String destination,Instant start,Instant end,String status,Instant expires,boolean calendar){
  public Slot(String id,String pickup,String destination,Instant start,Instant end,String status,Instant expires){this(id,pickup,destination,start,end,status,expires,false);}
 }
 public static boolean occupies(Slot s,Instant now){return Set.of("CONFIRMED","IN_PROGRESS").contains(s.status())||("PENDING".equals(s.status())&&s.expires().isAfter(now))||(s.calendar()&&Set.of("RETURNED","COMPLETED").contains(s.status())&&s.end().isAfter(now));}
 public static void validate(String currentBranch,List<Slot> rows,Instant now){
  var slots=rows.stream().filter(s->occupies(s,now)).sorted(Comparator.comparing(Slot::start)).toList();
  String position=currentBranch;Slot previous=null;
  for(Slot s:slots){
   if(previous!=null){
    if(previous.end().isAfter(s.start()))throw DomainException.conflict("Xe đã có đơn trùng khoảng thuê.");
    if("IN_PROGRESS".equals(previous.status())&&!previous.end().isAfter(now))throw DomainException.conflict("Xe quá hạn chưa trả, chưa thể bảo đảm lịch tiếp theo.");
    if("PENDING".equals(previous.status())&&!previous.pickup().equals(previous.destination()))throw DomainException.conflict("Cần xác nhận chuyến một chiều trước khi nhận đơn phụ thuộc nơi trả của chuyến đó.");
   }
   if(!position.equals(s.pickup()))throw DomainException.conflict("Xe không ở chi nhánh nhận hoặc chuyến này làm sai nơi nhận của đơn kế tiếp.");
   position=s.destination();previous=s;
  }
 }
}

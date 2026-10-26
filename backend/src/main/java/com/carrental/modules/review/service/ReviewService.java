package com.carrental.modules.review.service;
import com.carrental.modules.booking.service.BookingService;
import com.carrental.security.Actor;
import com.carrental.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
@Service public class ReviewService {
 private final BookingService bookings;private final JdbcTemplate jdbc;private final java.time.Clock clock;
 public ReviewService(BookingService b,JdbcTemplate j,java.time.Clock c){bookings=b;jdbc=j;clock=c;}
 @Transactional public void create(Actor a,String booking,int rating,String content){
  a.require("USER");var b=bookings.require(a,booking);
  if(!"COMPLETED".equals(b.status)||rating<1||rating>5||content==null||content.isBlank()||content.length()>1000)throw DomainException.bad("Chỉ đánh giá một lần cho đơn hoàn tất, 1–5 sao và nội dung hợp lệ.");
  jdbc.update("insert into reviews(id,booking_id,rating,content,hidden,created_at,version) values(?,?,?,?,false,?,0)",UUID.randomUUID().toString(),booking,rating,content.strip(),java.sql.Timestamp.from(clock.instant()));
 }
 public Object getForBooking(Actor a,String bookingId){
  var b=bookings.require(a,bookingId);
  var rows=jdbc.queryForList("select id,booking_id,rating,content,hidden,created_at,version from reviews where booking_id=?",b.id);
  return rows.isEmpty()?null:rows.get(0);
 }
 public Object publicList(String vehicle){
  var bookingIds=bookings.bookingIdsForVehicle(vehicle);
  if(bookingIds.isEmpty())return List.of();
  String inClause=String.join(",",Collections.nCopies(bookingIds.size(),"?"));
  return jdbc.queryForList("select id,rating,content,created_at from reviews where hidden=false and booking_id in ("+inClause+")",bookingIds.toArray());
 }
 public Object list(Actor a){a.require("ADMIN");return jdbc.queryForList("select id,booking_id,rating,content,hidden,version from reviews");}
 @Transactional public void moderate(Actor a,String id,boolean hidden,long version){
  a.require("ADMIN");if(jdbc.update("update reviews set hidden=?,moderated_by=?,moderated_at=?,version=version+1 where id=? and version=?",hidden,a.id(),java.sql.Timestamp.from(clock.instant()),id,version)!=1)throw DomainException.conflict("Đánh giá đã thay đổi.");
 }
}

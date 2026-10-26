package com.carrental.modules.review.controller;
import com.carrental.modules.review.service.ReviewService;
import com.carrental.security.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController @RequestMapping("/api/v1") public class ReviewController {
 private final ReviewService s;public ReviewController(ReviewService s){this.s=s;}
 public record Input(String bookingId,Integer rating,String content){}
 public record Moderation(Boolean hidden,Long version){}
 @PostMapping("/reviews") public void create(@AuthenticationPrincipal Actor a,@RequestBody Input i){
  if(i==null||i.rating()==null||i.bookingId()==null)throw com.carrental.exception.DomainException.bad("Thông tin đánh giá không đầy đủ.");
  s.create(a,i.bookingId(),i.rating(),i.content());
 }
 @GetMapping("/bookings/{id}/review") public Object forBooking(@AuthenticationPrincipal Actor a,@PathVariable String id){return s.getForBooking(a,id);}
 @GetMapping("/vehicles/{id}/reviews") public Object publicList(@PathVariable String id){return s.publicList(id);}
 @GetMapping("/admin/reviews") public Object list(@AuthenticationPrincipal Actor a){return s.list(a);}
 @PatchMapping("/admin/reviews/{id}") public void moderate(@AuthenticationPrincipal Actor a,@PathVariable String id,@RequestBody Moderation i){
  if(i==null||i.hidden()==null||i.version()==null)throw com.carrental.exception.DomainException.bad("Thông tin kiểm duyệt không đầy đủ.");
  s.moderate(a,id,i.hidden(),i.version());
 }
}

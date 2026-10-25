package com.carrental.modules.user.service;
import com.carrental.modules.user.entity.DriverLicense;
import com.carrental.modules.user.repository.DriverLicenseRepository;
import com.carrental.exception.DomainException;
import com.carrental.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
@Service public class LicenseService {
 @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
 private final DriverLicenseRepository repo;private final java.time.Clock clock;private final LicensePhotoStore photos;
 public LicenseService(DriverLicenseRepository r,java.time.Clock c,LicensePhotoStore p){repo=r;clock=c;photos=p;}
 public record PhotoReview(boolean front,boolean back,String status,String reason,String reviewedBy,Instant reviewedAt){}
 public record View(String id,String licenseNumber,String scheme,String licenseClass,LocalDate expiresOn,boolean automaticOnly,String status,long version,PhotoReview photos){
  public View(String id,String number,String scheme,String grade,LocalDate expiry,boolean auto,String status,long version){this(id,number,scheme,grade,expiry,auto,status,version,null);}
 }
 public View view(DriverLicense d){return new View(d.id,d.licenseNumber,d.scheme,d.licenseClass,d.expiresOn,d.automaticOnly,d.status,d.version,new PhotoReview(d.frontPhoto!=null,d.backPhoto!=null,d.photoReviewStatus,d.photoReviewReason,d.photoReviewedBy,d.photoReviewedAt));}
 public View mine(Actor a){return repo.findByUserId(a.id()).map(this::view).orElse(null);}
 @Transactional public View save(Actor a,View i){
  a.require("USER");
  if(i.licenseNumber()==null||i.licenseNumber().isBlank()||i.licenseNumber().length()>40||i.licenseClass()==null||i.scheme()==null||!LicensePolicy.compatible(i.scheme(),i.licenseClass(),i.automaticOnly(),"AUTO",5,i.expiresOn(),clock.instant()))throw DomainException.bad("GPLX không nằm trong phạm vi ô tô con hỗ trợ hoặc đã hết hạn.");
  var d=repo.findByUserId(a.id()).map(old->repo.lock(old.id).orElseThrow()).orElseGet(DriverLicense::new);
  if(d.userId!=null)entityManager.refresh(d,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  if(d.userId!=null&&d.version!=i.version())throw DomainException.conflict("GPLX đã thay đổi.");
  d.userId=a.id();d.licenseNumber=i.licenseNumber().strip();d.scheme=i.scheme();d.licenseClass=i.licenseClass();d.expiresOn=i.expiresOn();d.automaticOnly=i.automaticOnly();reset(d);
  return view(repo.saveAndFlush(d));
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
 public DriverLicense eligible(String userId,String transmission,int seats,Instant through){
  var d=repo.findByUserId(userId).orElseThrow(()->DomainException.bad("Vui lòng khai báo GPLX trước khi đặt xe."));
  entityManager.refresh(d,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  if("REJECTED".equals(d.status)||!LicensePolicy.compatible(d.scheme,d.licenseClass,d.automaticOnly,transmission,seats,d.expiresOn,through))throw DomainException.bad("GPLX không phù hợp xe hoặc không còn hiệu lực đến hết chuyến thuê.");
  return d;
 }
 @Transactional public View verify(String userId,Actor staff,String transmission,int seats,Instant through){
  staff.require("STAFF");var existing=repo.findByUserId(userId).orElseThrow(DomainException::missing);
  var d=repo.lock(existing.id).orElseThrow(DomainException::missing);requirePhotos(userId,true);eligible(userId,transmission,seats,through);
  d.status="VERIFIED";d.checkedBy=staff.id();d.checkedAt=clock.instant();repo.flush();return view(d);
 }
 public View forBooking(String userId){return repo.findByUserId(userId).map(this::view).orElse(null);}
 private void reset(DriverLicense d){
  d.status="PENDING";d.checkedAt=null;d.checkedBy=null;
  d.photoReviewStatus=d.frontPhoto!=null&&d.backPhoto!=null?"PENDING":"MISSING";
  d.photoReviewReason=null;d.photoReviewedBy=null;d.photoReviewedAt=null;
 }
 private DriverLicense locked(String userId,long version){
  var old=repo.findByUserId(userId).orElseThrow(()->DomainException.bad("Vui lòng lưu thông tin GPLX trước."));
  var d=repo.lock(old.id).orElseThrow(DomainException::missing);
  entityManager.refresh(d,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  if(d.version!=version)throw DomainException.conflict("GPLX đã thay đổi. Vui lòng tải lại.");
  return d;
 }
 @Transactional public View upload(Actor a,long version,org.springframework.web.multipart.MultipartFile front,org.springframework.web.multipart.MultipartFile back){
  a.require("USER");
  byte[] f=photos.normalize(front),b=photos.normalize(back);
  var d=locked(a.id(),version);
  String oldFront=d.frontPhoto,oldBack=d.backPhoto;
  var created=new java.util.ArrayList<String>();
  org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
   @Override public void afterCompletion(int status){
    var keys=status==STATUS_COMMITTED?java.util.Arrays.asList(oldFront,oldBack):created;
    for(var key:keys)try{photos.remove(key);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(LicenseService.class).error("GPLX: cần thử lại dọn ảnh riêng tư sau giao dịch.");}
   }
  });
  d.frontPhoto=photos.write(f);created.add(d.frontPhoto);
  d.backPhoto=photos.write(b);created.add(d.backPhoto);reset(d);
  return view(repo.saveAndFlush(d));
 }
 @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
 public void requirePhotos(String userId,boolean approved){
  var d=repo.findByUserId(userId).orElseThrow(DomainException::missing);
  entityManager.refresh(d,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  if(d.frontPhoto==null||d.backPhoto==null)throw DomainException.bad("Vui lòng tải đủ ảnh hai mặt GPLX trong Hồ sơ & GPLX trước khi gửi yêu cầu thuê.");
  if(approved&&!"APPROVED".equals(d.photoReviewStatus))throw DomainException.bad("Cần kiểm tra và duyệt hồ sơ ảnh GPLX trước khi xác nhận/giao xe.");
  photos.read(d.frontPhoto);photos.read(d.backPhoto);
 }
 @Transactional public View review(String userId,Actor staff,long version,boolean approve,String reason){
  staff.require("STAFF");var d=locked(userId,version);requirePhotos(userId,false);
  if(reason==null||reason.isBlank()||reason.length()>1000)throw DomainException.bad("Cần ghi kết quả kiểm tra, tối đa 1000 ký tự.");
  // Recheck stored objects: a missing file cannot be approved from metadata alone.
  photos.read(d.frontPhoto);photos.read(d.backPhoto);
  d.photoReviewStatus=approve?"APPROVED":"NEEDS_CHANGES";d.photoReviewReason=reason.strip();d.photoReviewedBy=staff.id();d.photoReviewedAt=clock.instant();
  d.status="PENDING";d.checkedBy=null;d.checkedAt=null;
  return view(repo.saveAndFlush(d));
 }
 public byte[] photo(String userId,String side){
  var d=repo.findByUserId(userId).orElseThrow(DomainException::missing);
  return photos.read(switch(side){case "front"->d.frontPhoto;case "back"->d.backPhoto;default->throw DomainException.missing();});
 }
 public byte[] ownPhoto(Actor a,String side){a.require("USER");return photo(a.id(),side);}

}

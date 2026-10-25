package com.carrental.modules.user.service;
import java.time.*;
import java.util.Set;
public final class LicensePolicy {
 private LicensePolicy(){}
 // Scope: Vietnamese passenger cars, 2..9 seats INCLUDING driver. Unknown schemes/classes fail closed.
 public static boolean compatible(String scheme,String grade,boolean automaticOnly,String transmission,int seats,LocalDate expires,Instant through){
  if(expires==null||through==null||seats<2||seats>9||expires.isBefore(through.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()))return false;
  if(automaticOnly&&!"AUTO".equals(transmission))return false;
  if("VN_2025".equals(scheme))return Set.of("B","C1","C","D1","D2","D","BE","C1E","CE","D1E","D2E","DE").contains(grade);
  if("VN_PRE_2025".equals(scheme)){
   if("B1_AUTO".equals(grade))return "AUTO".equals(transmission);
   return Set.of("B1","B2","C","D","E","FB2","FC","FD","FE").contains(grade);
  }
  return false;
 }
}

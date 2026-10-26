package com.carrental.exception;
import org.springframework.http.HttpStatus;
public class DomainException extends RuntimeException {
 public final HttpStatus status;
 public DomainException(HttpStatus status, String message) { super(message); this.status=status; }
 public static DomainException bad(String m){return new DomainException(HttpStatus.BAD_REQUEST,m);}
 public static DomainException conflict(String m){return new DomainException(HttpStatus.CONFLICT,m);}
 public static DomainException forbidden(){return new DomainException(HttpStatus.FORBIDDEN,"Không có quyền thực hiện thao tác.");}
 public static DomainException missing(){return new DomainException(HttpStatus.NOT_FOUND,"Không tìm thấy dữ liệu.");}
}

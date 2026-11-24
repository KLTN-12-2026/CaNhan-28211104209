package com.carrental.exception;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.UUID;
@RestControllerAdvice
public class ApiErrors {
 private static final Logger LOGGER=LoggerFactory.getLogger(ApiErrors.class);
 @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
 ResponseEntity<?> uploadTooLarge(Exception e){return ResponseEntity.status(413).body(Map.of("message","Mỗi ảnh GPLX tối đa 5 MB."));}
 @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
 ResponseEntity<?> missingPart(Exception e){return ResponseEntity.badRequest().body(Map.of("message","Cần chọn đủ hai mặt ảnh GPLX."));}
 @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
 ResponseEntity<?> unsupportedMethod(Exception e){return ResponseEntity.status(405).body(Map.of("message","Phương thức không được hỗ trợ."));}
 @ExceptionHandler(DomainException.class) ResponseEntity<?> domain(DomainException e){return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage()));}
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e){
  return ResponseEntity.badRequest().body(Map.of("message","Dữ liệu không hợp lệ.","fields",e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).toList()));
 }
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
 ResponseEntity<?> malformed(Exception e,HttpServletRequest request){
  // Exception messages may echo request values; log only the type and route.
  LOGGER.warn("api_rejected status=400 type={} method={} path={}",e.getClass().getName(),request.getMethod(),request.getRequestURI());
  return ResponseEntity.badRequest().body(Map.of("message","Định dạng dữ liệu không hợp lệ."));
 }
 /** A NullPointerException is a server defect, never a client format error: report 500 and keep the stack trace. */
 @ExceptionHandler(NullPointerException.class)
 ResponseEntity<?> serverDefect(NullPointerException e,HttpServletRequest request){
  String reference=UUID.randomUUID().toString().substring(0,8);
  LOGGER.error("api_server_error status=500 reference={} method={} path={}",reference,request.getMethod(),request.getRequestURI(),e);
  return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message","Lỗi hệ thống. Vui lòng thử lại; nếu lặp lại, báo mã "+reference+".","reference",reference));
 }
 @ExceptionHandler({DataIntegrityViolationException.class,ObjectOptimisticLockingFailureException.class})
 ResponseEntity<?> conflict(Exception e,HttpServletRequest request){
  // SQL messages can contain row values; the type is enough to tell a constraint from a lock conflict.
  LOGGER.warn("api_rejected status=409 type={} method={} path={}",e.getClass().getName(),request.getMethod(),request.getRequestURI());
  return ResponseEntity.status(409).body(Map.of("message","Dữ liệu trùng hoặc đã thay đổi. Vui lòng tải lại."));
 }
}

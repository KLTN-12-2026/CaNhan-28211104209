package com.carrental.exception;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.Map;
@RestControllerAdvice
public class ApiErrors {
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
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,IllegalArgumentException.class,NullPointerException.class})
 ResponseEntity<?> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("message","Định dạng dữ liệu không hợp lệ."));}
 @ExceptionHandler({DataIntegrityViolationException.class,ObjectOptimisticLockingFailureException.class})
 ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("message","Dữ liệu trùng hoặc đã thay đổi. Vui lòng tải lại."));}
}

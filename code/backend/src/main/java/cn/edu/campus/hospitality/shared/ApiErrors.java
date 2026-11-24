package cn.edu.campus.hospitality.shared;

import static cn.edu.campus.hospitality.shared.Data.map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts business and request parsing failures to the API error format. */
@RestControllerAdvice
public class ApiErrors {
  /** Returns the status, code, message, and details of a rejected business operation. */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> business(BusinessException ex) {
    return ResponseEntity.status(ex.httpStatus())
        .body(map("businessCode", ex.code(), "message", ex.getMessage(), "data", ex.data()));
  }

  /** Returns a validation error for invalid dates, amounts, or request content. */
  @ExceptionHandler({
    IllegalArgumentException.class,
    ArithmeticException.class,
    java.time.DateTimeException.class,
    ClassCastException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<?> invalid(Exception ex) {
    return ResponseEntity.badRequest()
        .body(map("businessCode", "VALIDATION", "message", "输入格式不正确，请检查日期、金额和数量"));
  }
}

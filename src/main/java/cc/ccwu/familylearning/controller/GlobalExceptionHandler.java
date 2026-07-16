package cc.ccwu.familylearning.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import cc.ccwu.familylearning.service.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final UsageService usage;
    public GlobalExceptionHandler(UsageService usage){this.usage=usage;}

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String,Object>> forbidden(SecurityException exception){
        return response(HttpStatus.UNAUTHORIZED,exception.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception exception) {
        usage.backendError();
        log.error("Unhandled API error", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "系统暂时开小差了，请稍后再试");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}

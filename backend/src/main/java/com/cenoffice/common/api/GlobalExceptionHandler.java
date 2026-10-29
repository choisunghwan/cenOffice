package com.cenoffice.common.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) { this.audit = audit; }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).findFirst().orElse("잘못된 요청입니다");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "요청 본문을 해석할 수 없습니다"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> notFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "요청한 경로가 없습니다"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> methodNotAllowed(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", "허용되지 않는 요청 방식입니다"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        audit.log(auth == null ? "anonymous" : auth.getName(), "ACCESS_DENIED", null, null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "권한이 없습니다"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("Unhandled", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "서버 내부 오류"));
    }
}

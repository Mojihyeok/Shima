package kr.ac.ajou.shima.controller;

import kr.ac.ajou.shima.auth.UnauthorizedException;
import kr.ac.ajou.shima.diagnosis.ExternalApiException;
import kr.ac.ajou.shima.service.StampService.ConsentRequiredException;
import kr.ac.ajou.shima.service.StampService.TokenExpiredException;
import kr.ac.ajou.shima.service.StampService.TokenInvalidException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TokenInvalidException.class)
    public ResponseEntity<Map<String, String>> invalid(TokenInvalidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "token_invalid", "message", e.getMessage()));
    }

    /** 네이버 API 키 미설정 → 503, 호출 실패 → 502 */
    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<Map<String, String>> external(ExternalApiException e) {
        HttpStatus status = e.isConfigured() ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(Map.of("error", e.isConfigured() ? "external_api_failed" : "external_api_not_configured", "message", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized", "message", e.getMessage()));
    }

    /** 처음 적립 — 프론트가 동의 화면을 띄운 뒤 agreed=true로 다시 요청 */
    @ExceptionHandler(ConsentRequiredException.class)
    public ResponseEntity<Map<String, String>> consent(ConsentRequiredException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(Map.of("error", "consent_required", "message", e.getMessage()));
    }

    /** 잘못된 입력 (전화번호 형식, 다른 매장 손님 등) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", e.getMessage()));
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<Map<String, String>> expired(TokenExpiredException e) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "token_expired", "message", e.getMessage()));
    }
}

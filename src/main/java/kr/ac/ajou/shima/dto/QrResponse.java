package kr.ac.ajou.shima.dto;

import java.time.Instant;

/** 사장님 화면용 QR 정보 — URL 조립은 프론트에서 (손님 웹 주소가 환경마다 다르므로) */
public record QrResponse(
        String storeName,
        String token,
        String kind,        // ROTATING | PRINT
        Instant expiresAt
) {}

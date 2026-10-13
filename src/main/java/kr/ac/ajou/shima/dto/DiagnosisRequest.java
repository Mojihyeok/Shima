package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.Min;

/** 진단 요청 — 모두 선택. 비우면 매장 정보 사용. 리뷰 수는 네이버 지도에서 사장님이 보고 입력(공식 API 없음) */
public record DiagnosisRequest(
        String storeName,
        String region,
        @Min(0) Integer visitorReviewCount
) {}

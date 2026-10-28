package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotBlank;

/** 손님 적립 요청 — 전화번호 전체 입력(회원가입 없음), 이름/생일/유입경로는 선택 */
public record StampRequest(
        @NotBlank String phone,   // 숫자만 또는 하이픈 포함, 서버에서 정규화
        String name,
        String birthday,          // "MM-dd" (선택)
        String source,            // 유입 경로 자기보고 (선택): instagram/blog/friend/pass
        Boolean agreed,           // 개인정보 수집·이용 동의 — 처음 적립(또는 동의 기록 없음)일 때만 필요
        Integer elapsedMs         // QR 화면 열린 뒤 적립 버튼까지 걸린 시간 (PoC 측정, 선택)
) {}

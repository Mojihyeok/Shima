package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotBlank;

/** 유입 경로 응답 — 첫 적립 직후 손님 화면에서 (적립에 쓴 전화번호로 손님 식별) */
public record SourceRequest(@NotBlank String phone, @NotBlank String source) {}

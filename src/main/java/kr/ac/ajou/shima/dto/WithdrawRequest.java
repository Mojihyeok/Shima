package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotBlank;

/** 손님 본인 정보 삭제 요청 — 적립할 때 쓴 전화번호 */
public record WithdrawRequest(@NotBlank String phone) {}

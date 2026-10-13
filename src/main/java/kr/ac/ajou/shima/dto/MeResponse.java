package kr.ac.ajou.shima.dto;

/** 로그인한 사장님 정보 — 화면 모드(EASY/STANDARD) 포함 */
public record MeResponse(String loginId, String storeName, String uiMode) {}

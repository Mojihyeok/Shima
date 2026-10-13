package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotBlank;

/** 사장님 재량 서비스 부여 요청 */
public record GrantRequest(
        @NotBlank String rewardName,
        String memo
) {}

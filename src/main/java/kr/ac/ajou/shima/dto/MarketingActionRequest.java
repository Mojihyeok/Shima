package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 마케팅 실행 기록 — date 생략 시 오늘 */
public record MarketingActionRequest(
        @NotBlank String actionType,   // MarketingService.TYPES 중 하나
        LocalDate date,
        @Size(max = 100) String memo
) {}

package kr.ac.ajou.shima.dto;

import java.time.LocalDate;

public record MarketingActionResponse(
        Long id,
        String actionType,
        String source,     // F3(AI 과제 수행) / MANUAL(사장님 직접)
        LocalDate date,    // 한국 날짜
        String memo
) {}

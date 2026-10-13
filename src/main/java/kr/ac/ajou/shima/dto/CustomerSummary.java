package kr.ac.ajou.shima.dto;

import java.time.Instant;

/** 단골 목록 / 오늘 방문 손님 항목 */
public record CustomerSummary(
        Long customerId,
        String phoneLast4,
        String name,            // 없으면 null
        int visitCount,         // 누적 방문(도장) 수
        Instant firstVisitAt,
        Instant lastVisitAt
) {}

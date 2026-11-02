package kr.ac.ajou.shima.dto;

import java.time.Instant;

/** 이탈 위험 단골 — "보통 avgCycleDays일마다 오시는데 daysSinceLast일째" */
public record AtRiskCustomer(
        Long customerId,
        String phoneLast4,
        String name,
        int visitCount,
        double avgCycleDays,     // 이 손님의 평소 방문 간격
        int daysSinceLast,       // 마지막 방문 후 지난 날
        double overdueRatio,     // daysSinceLast ÷ avgCycleDays (클수록 오래 안 옴)
        Instant lastVisitAt
) {}

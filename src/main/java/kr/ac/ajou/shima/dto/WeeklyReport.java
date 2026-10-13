package kr.ac.ajou.shima.dto;

import java.time.LocalDate;

/** 주간 한 장 요약 (방문 데이터 기반 — 매출 입력 없이도 성립) */
public record WeeklyReport(
        LocalDate weekStart,
        LocalDate weekEnd,
        boolean inProgress,
        int newCount,           // 처음 온 손님
        int revisitCount,       // 다시 온 손님
        int visitors,           // 방문 손님 (중복 제거)
        int prevVisitors,       // 지난주 방문 손님
        Double changePct,       // 지난주 대비 증감률(%) — 지난주 0명이면 null
        Double revisitRate,     // 이번 주 방문 손님 중 다시 온 손님 비율 (0~1) — 방문 0명이면 null
        Double avgCycleDays,    // 주말 기준, 2회 이상 방문 손님의 평균 방문 간격(일) — 대상 없으면 null
        long rewardsAuto,       // 자동 리워드 지급 수
        long rewardsManual      // 사장님 재량 서비스 수
) {}

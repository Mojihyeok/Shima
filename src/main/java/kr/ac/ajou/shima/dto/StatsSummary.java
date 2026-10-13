package kr.ac.ajou.shima.dto;

/** 대시보드 상단 요약 — 이번주 신규/재방문, 전주 대비 증감 */
public record StatsSummary(
        int newThisWeek,        // 이번주 새 손님
        int revisitThisWeek,    // 이번주 다시 온 손님
        int visitorsThisWeek,   // 이번주 방문 손님(중복 제거)
        int visitorsLastWeek,   // 지난주 방문 손님
        int deltaVisitors       // 지난주 대비 증감 (양수=증가)
) {}

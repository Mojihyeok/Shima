package kr.ac.ajou.shima.dto;

import java.time.LocalDate;

/** 주별 방문 추이 한 점 — 신규 + 재방문 = 그 주 방문 손님(중복 제거) */
public record WeekPoint(
        LocalDate weekStart,    // 월요일
        int newCount,
        int revisitCount,
        boolean inProgress      // 이번 주(아직 진행 중)
) {}

package kr.ac.ajou.shima.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * PoC 성공 지표 묶음 (기간: from ~ to, 양끝 포함, 한국 날짜 기준).
 * null = 계산할 데이터가 아직 없음.
 */
public record PocMetrics(
        LocalDate from,
        LocalDate to,
        // 적립 참여율 = 손님 수를 입력한 날의 QR 적립 ÷ 그날 전체 손님 수
        int qrVisits,                 // 기간 전체 QR 적립 수
        int daysWithCount,            // 전체 손님 수를 입력한 날 수
        int enteredVisitors,          // 입력한 전체 손님 수 합
        int qrVisitsOnCountedDays,    // 입력한 날의 QR 적립 수
        Double participationRate,     // 0~1, 목표 0.3
        // 적립 소요 시간
        int timedStamps,              // 시간이 기록된 적립 수
        Double medianStampSeconds,
        Double p90StampSeconds,
        Double within10sRate,         // 10초 이내 비율, 목표 높을수록
        // 재방문
        int newCustomers,             // 기간 중 첫 방문한 손님
        int returnedWithin14Days,     // 그중 첫 방문 후 14일 안에 다시 온 손님
        // 사장님 사용
        long reportViews,
        long diagnosisViews,
        long manualGrants,            // 재량 서비스
        long autoGrants,              // 도장 리워드
        // 유입 경로 (첫 방문 손님 기준) — 응답 안 함은 "none"
        Map<String, Integer> sources
) {}

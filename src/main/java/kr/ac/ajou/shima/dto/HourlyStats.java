package kr.ac.ajou.shima.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 시간대별 방문 (QR 적립 시각 기준, 한국 시간, 1시간 단위).
 * 하루 평균 = 합계 ÷ 기간 중 날짜 수 (평일·주말 따로) — 화면에서 계산.
 */
public record HourlyStats(
        LocalDate from,
        LocalDate to,
        int weekdayDays,      // 기간 중 평일 날짜 수 (분모)
        int weekendDays,      // 기간 중 주말 날짜 수 (분모)
        List<Hour> hours      // 0~23시
) {
    public record Hour(int hour, int weekdayTotal, int weekendTotal) {}
}

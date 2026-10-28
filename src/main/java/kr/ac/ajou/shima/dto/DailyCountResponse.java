package kr.ac.ajou.shima.dto;

import java.time.LocalDate;

/** count == null 이면 아직 입력 안 함 */
public record DailyCountResponse(LocalDate date, Integer count) {}

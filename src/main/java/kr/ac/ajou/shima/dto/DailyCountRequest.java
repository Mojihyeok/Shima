package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** 하루 전체 손님 수 입력 — date 생략 시 오늘 */
public record DailyCountRequest(LocalDate date, @NotNull @Min(0) @Max(5000) Integer count) {}

package kr.ac.ajou.shima.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 우리 가게 이야기 — 사장님 고민·목표 + 가게 특징 (AI 처방·글쓰기가 참고) */
public record StoreProfile(
        @Size(max = 300) String ownerGoal,
        @Size(max = 1000) String storeNotes,
        Instant updatedAt
) {}

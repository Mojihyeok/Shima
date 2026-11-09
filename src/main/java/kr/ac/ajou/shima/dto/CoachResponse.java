package kr.ac.ajou.shima.dto;

import java.time.Instant;
import java.util.List;

/** AI 처방 — 이번 주 할 일 1개 */
public record CoachResponse(
        Long id,
        Instant createdAt,
        String status,          // PENDING | DONE | SKIPPED
        String source,          // AI | RULE(규칙 기반 대체)
        String taskType,        // CoachService.TASKS 중 하나
        String title,           // 이번 주 할 일 (한 줄)
        String why,             // 이유 — 진단·통계 숫자 근거
        List<String> steps,     // 실행 단계 (최대 3)
        List<String> drafts,    // 문구 초안 (최대 3)
        List<AtRiskCustomer> audience, // 안부 대상 (winback일 때만, 서버가 붙임 — AI에는 개인정보 안 보냄)
        Integer chosenDraft
) {}

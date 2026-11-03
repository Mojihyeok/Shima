package kr.ac.ajou.shima.dto;

import java.time.Instant;
import java.util.List;

/** AI 언급률 — "AI에게 asked번 물어봤더니 mentioned번 우리 가게를 추천" (참고 지표, 점수 미포함) */
public record AiMentionResponse(
        Instant checkedAt,
        String model,
        int asked,
        int mentioned,
        List<Item> items
) {
    public record Item(
            String question,
            boolean mentioned,
            String answerExcerpt,   // AI 답변 앞부분 (최대 400자)
            int webSearches
    ) {}
}

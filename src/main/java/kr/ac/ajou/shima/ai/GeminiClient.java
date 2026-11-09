package kr.ac.ajou.shima.ai;

import com.fasterxml.jackson.databind.JsonNode;
import kr.ac.ajou.shima.diagnosis.ExternalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini API 최소 클라이언트 — Interactions API (POST /v1beta/interactions).
 * 키는 환경변수 GEMINI_API_KEY로만. 모델은 SHIMA_AI_MODEL (기본 gemini-3.8-flash).
 * Gemini 3.6+는 temperature/top_p/top_k를 무시하므로 보내지 않는다.
 *
 * 구글 검색 연동(grounding): tools=[{"type":"google_search"}]
 *  - Gemini 3 이상: 검색 쿼리당 과금, 월 5,000회 무료(프로젝트 공유) 이후 1,000회당 14달러
 *  - 검색 결과를 사용자에게 보여줄 때 search_suggestions(HTML)를 함께 표시해야 함 (Gemini API 약관)
 *
 * 응답 구조: steps[] — type = thought | google_search_call(arguments.queries) |
 *            google_search_result(result[].search_suggestions) | model_output(content[].text)
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** text: 최종 답변 / webSearches: 실행한 검색 쿼리 수 / suggestionsHtml: 구글 검색 제안 HTML(표시 의무) */
    public record Answer(String text, int webSearches, String suggestionsHtml) {}

    private final RestClient http;
    private final boolean configured;
    private final String model;

    public GeminiClient(@Value("${shima.ai.api-key:}") String apiKey,
                        @Value("${shima.ai.model:gemini-3.8-flash}") String model) {
        this.configured = !apiKey.isBlank();
        this.model = model;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(120_000); // 검색이 들어가면 수십 초 걸릴 수 있음
        this.http = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .requestFactory(f)
                .defaultHeader("x-goog-api-key", apiKey)
                .build();
    }

    public boolean isConfigured() { return configured; }
    public String model() { return model; }

    /** 구글 검색을 허용한 한 번의 질문 → 최종 텍스트 */
    public Answer askWithSearch(String system, String question, int maxOutputTokens) {
        return generate(system, question, true, maxOutputTokens);
    }

    /** 검색 없이 한 번의 질문 (AI 처방 등 — 우리가 넘긴 데이터만으로 답하게) */
    public Answer ask(String system, String input, int maxOutputTokens) {
        return generate(system, input, false, maxOutputTokens);
    }

    private Answer generate(String system, String input, boolean search, int maxOutputTokens) {
        if (!configured) throw new ExternalApiException("Gemini API 키가 설정되지 않았어요. (GEMINI_API_KEY)", false);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("system_instruction", system);
        body.put("input", input);
        if (search) body.put("tools", List.of(Map.of("type", "google_search")));
        body.put("generation_config", Map.of("max_output_tokens", maxOutputTokens, "thinking_level", "low"));

        JsonNode res = post(body);

        StringBuilder text = new StringBuilder();
        int searches = 0;
        String suggestions = null;
        for (JsonNode step : res.path("steps")) {
            switch (step.path("type").asText()) {
                case "model_output" -> {
                    for (JsonNode c : step.path("content")) {
                        if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
                    }
                }
                case "google_search_call" -> {
                    int n = step.path("arguments").path("queries").size();
                    searches += Math.max(1, n);
                }
                case "google_search_result" -> {
                    for (JsonNode r : step.path("result")) {
                        if (suggestions == null && r.hasNonNull("search_suggestions")) {
                            suggestions = r.path("search_suggestions").asText();
                        }
                    }
                }
                default -> { /* thought 등은 무시 */ }
            }
        }
        // 응답 구조가 다를 때를 대비한 보조 경로 (SDK의 output_text, 예전 generateContent 형식)
        if (text.length() == 0 && res.hasNonNull("output_text")) text.append(res.path("output_text").asText());
        if (text.length() == 0) {
            for (JsonNode p : res.path("candidates").path(0).path("content").path("parts")) text.append(p.path("text").asText(""));
        }
        if (text.length() == 0) {
            String raw = res.toString();
            log.warn(">> [gemini] 답변 텍스트를 찾지 못함: {}", raw.substring(0, Math.min(300, raw.length())));
        }
        return new Answer(text.toString().trim(), searches, suggestions);
    }

    private JsonNode post(Map<String, Object> body) {
        try {
            JsonNode r = http.post().uri("/v1beta/interactions").body(body).retrieve().body(JsonNode.class);
            if (r == null) throw new ExternalApiException("Gemini API 응답이 비었어요.", true);
            return r;
        } catch (RestClientResponseException e) {
            log.warn(">> [gemini] HTTP {} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ExternalApiException("Gemini API 오류 (" + e.getStatusCode().value() + ")", true);
        } catch (RestClientException e) {
            log.warn(">> [gemini] 호출 실패: {}", e.toString(), e);
            throw new ExternalApiException("Gemini API에 연결하지 못했어요.", true);
        }
    }
}

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude Messages API 최소 클라이언트 (공식 SDK 없이 RestClient).
 * 키는 환경변수 ANTHROPIC_API_KEY로만. 모델은 SHIMA_AI_MODEL (기본 claude-sonnet-5-5).
 * Sonnet 5.5는 temperature 등을 받지 않으므로 보내지 않는다.
 */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    /** 웹 검색 도구 기본 버전 — 직접 호출(allowed_callers 기본 direct) */
    public static final String WEB_SEARCH_TOOL = "web_search_20250305";

    public record Answer(String text, int webSearches, String stopReason) {}

    private final RestClient http;
    private final boolean configured;
    private final String model;

    public ClaudeClient(@Value("${shima.ai.api-key:}") String apiKey,
                        @Value("${shima.ai.model:claude-sonnet-5-5}") String model) {
        this.configured = !apiKey.isBlank();
        this.model = model;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(120_000); // 웹 검색이 들어가면 수십 초 걸릴 수 있음
        this.http = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .requestFactory(f)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    public boolean isConfigured() { return configured; }
    public String model() { return model; }

    /**
     * 웹 검색을 허용한 한 번의 질문 → 최종 텍스트.
     * stop_reason=pause_turn(긴 검색 중 일시정지)이면 받은 내용을 그대로 붙여 최대 2번 이어서 요청.
     */
    public Answer askWithWebSearch(String system, String question, int maxSearches) {
        if (!configured) throw new ExternalApiException("Claude API 키가 설정되지 않았어요. (ANTHROPIC_API_KEY)", false);

        Map<String, Object> tool = new HashMap<>();
        tool.put("type", WEB_SEARCH_TOOL);
        tool.put("name", "web_search");
        tool.put("max_uses", maxSearches);
        tool.put("user_location", Map.of("type", "approximate", "country", "KR", "timezone", "Asia/Seoul"));

        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", question));

        StringBuilder text = new StringBuilder();
        int searches = 0;
        String stop = null;
        for (int turn = 0; turn < 3; turn++) {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("max_tokens", 1500);
            body.put("system", system);
            body.put("tools", List.of(tool));
            body.put("messages", messages);

            JsonNode res = post(body);
            stop = res.path("stop_reason").asText(null);
            searches += res.path("usage").path("server_tool_use").path("web_search_requests").asInt(0);
            for (JsonNode block : res.path("content")) {
                if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            }
            if (!"pause_turn".equals(stop)) break;
            // 일시정지: 받은 content를 assistant 턴으로 그대로 붙여 이어가기
            messages.add(Map.of("role", "assistant", "content", res.path("content")));
        }
        return new Answer(text.toString().trim(), searches, stop);
    }

    private JsonNode post(Map<String, Object> body) {
        try {
            JsonNode r = http.post().uri("/v1/messages").body(body).retrieve().body(JsonNode.class);
            if (r == null) throw new ExternalApiException("Claude API 응답이 비었어요.", true);
            return r;
        } catch (RestClientResponseException e) {
            log.warn(">> [claude] HTTP {} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ExternalApiException("Claude API 오류 (" + e.getStatusCode().value() + ")", true);
        } catch (RestClientException e) {
            log.warn(">> [claude] 호출 실패: {}", e.toString(), e);
            throw new ExternalApiException("Claude API에 연결하지 못했어요.", true);
        }
    }
}

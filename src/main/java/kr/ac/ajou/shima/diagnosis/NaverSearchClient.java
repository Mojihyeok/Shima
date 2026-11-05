package kr.ac.ajou.shima.diagnosis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.function.Supplier;

/**
 * 네이버 검색 API (공식, NAVER API HUB) — 지역 검색 / 블로그 검색.
 *  ※ 2026-07-31부터 개발자센터(openapi.naver.com) 신규 신청이 종료되고 네이버 클라우드 플랫폼의
 *    NAVER API HUB로 이관됨 → 도메인·경로·인증 헤더만 바뀌고 파라미터·응답 형식은 동일.
 *  - 지역: display 최대 5, start 1 고정, 별점·리뷰 수 미제공, telephone은 빈 값이 흔함
 *  - 블로그: display 최대 100, sort=sim(정확도)|date(최신), postdate=yyyyMMdd
 *  - 호출 한도는 NAVER API HUB 정책을 따름 (초과 시 429)
 * 크롤링은 하지 않는다 (ToS). 공식 API로 얻을 수 있는 것만 사용.
 *  ※ API HUB는 JSON 본문을 Content-Type text/plain으로 보냄 → 문자열로 받아 직접 JSON 변환
 */
@Component
public class NaverSearchClient {

    private static final Logger log = LoggerFactory.getLogger(NaverSearchClient.class);

    public record LocalItem(String title, String link, String category, String description,
                            String telephone, String address, String roadAddress, String mapx, String mapy) {}
    public record LocalResponse(List<LocalItem> items) {}

    public record BlogItem(String title, String link, String description, String bloggername, String postdate) {}
    public record BlogResponse(long total, List<BlogItem> items) {}

    private final RestClient http;
    private final boolean configured;
    private final ObjectMapper objectMapper;

    public NaverSearchClient(@Value("${shima.naver.client-id:}") String clientId,
                             @Value("${shima.naver.client-secret:}") String clientSecret,
                             ObjectMapper objectMapper) {
        this.configured = !clientId.isBlank() && !clientSecret.isBlank();
        this.objectMapper = objectMapper;
        this.http = RestClient.builder()
                .baseUrl("https://naverapihub.apigw.ntruss.com")
                .defaultHeader("X-NCP-APIGW-API-KEY-ID", clientId)
                .defaultHeader("X-NCP-APIGW-API-KEY", clientSecret)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public boolean isConfigured() { return configured; }

    /** 지역 검색 상위 5곳 (정확도순) */
    public List<LocalItem> local(String query) {
        LocalResponse r = parse(call(() -> http.get()
                .uri(u -> u.path("/search/v1/local")
                        .queryParam("query", "{q}").queryParam("display", 5).queryParam("sort", "random")
                        .build(query)) // 값은 URI 변수로 넘겨 &, + 같은 문자도 안전하게 인코딩
                .retrieve().body(String.class)), LocalResponse.class);
        return r == null || r.items() == null ? List.of() : r.items();
    }

    /** 블로그 검색 최대 100건. sort = "sim" | "date" */
    public BlogResponse blog(String query, String sort) {
        BlogResponse r = parse(call(() -> http.get()
                .uri(u -> u.path("/search/v1/blog")
                        .queryParam("query", "{q}").queryParam("display", 100).queryParam("sort", "{s}")
                        .build(query, sort))
                .retrieve().body(String.class)), BlogResponse.class);
        return r == null ? new BlogResponse(0, List.of()) : (r.items() == null ? new BlogResponse(r.total(), List.of()) : r);
    }

    /** 문자열 응답 → JSON 변환 (Content-Type과 무관하게) */
    private <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            log.warn(">> [naver] 응답 JSON 변환 실패: {} / 앞부분={}", e.getOriginalMessage(),
                    body.substring(0, Math.min(200, body.length())));
            throw new ExternalApiException("네이버 검색 API 응답을 읽지 못했어요.", true);
        }
    }

    private <T> T call(Supplier<T> s) {
        if (!configured) {
            throw new ExternalApiException("네이버 검색 API 키가 설정되지 않았어요. (NAVER_CLIENT_ID / NAVER_CLIENT_SECRET)", false);
        }
        try {
            return s.get();
        } catch (RestClientResponseException e) {
            log.warn(">> [naver] HTTP {} body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ExternalApiException("네이버 검색 API 오류 (" + e.getStatusCode().value() + ")", true);
        } catch (RestClientException e) {
            // 연결 실패뿐 아니라 응답 변환 실패도 여기로 옴 → 원인 확인용 로그 (키는 찍지 않음)
            log.warn(">> [naver] 호출 실패: {}", e.toString(), e);
            throw new ExternalApiException("네이버 검색 API에 연결하지 못했어요.", true);
        }
    }

    /** 응답의 <b> 태그·HTML 엔티티 제거 */
    public static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#39;", "'").trim();
    }

    /** 비교용 정규화 — 태그 제거, 공백 제거, 소문자 */
    public static String norm(String s) {
        return clean(s).replaceAll("\\s+", "").toLowerCase();
    }
}

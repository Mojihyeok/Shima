package kr.ac.ajou.shima.diagnosis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * 카카오 로컬 API — 키워드로 장소 검색 (공식 API, 무료 쿼터 내).
 * 진단에서 "카카오맵에서도 찾기 쉬운가"(SEO 보강)와 "네이버·카카오 정보가 일치하는가"(AI 노출 준비도)에 사용.
 * 키가 없거나 실패해도 진단 전체를 막지 않도록 호출 쪽에서 예외를 잡아 해당 항목만 측정 제외한다.
 */
@Component
public class KakaoLocalClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoPlace(
            @JsonProperty("place_name") String placeName,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("phone") String phone,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            @JsonProperty("place_url") String placeUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeywordResponse(List<KakaoPlace> documents) {}

    private final RestClient http;
    private final boolean configured;

    public KakaoLocalClient(@Value("${shima.kakao.rest-api-key:}") String restApiKey) {
        this.configured = !restApiKey.isBlank();
        this.http = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + restApiKey)
                .build();
    }

    public boolean isConfigured() { return configured; }

    /** 키워드 검색 상위 size곳 (정확도순, 최대 15) */
    public List<KakaoPlace> keyword(String query, int size) {
        if (!configured) throw new ExternalApiException("카카오 REST API 키가 설정되지 않았어요. (KAKAO_REST_API_KEY)", false);
        try {
            KeywordResponse r = http.get()
                    .uri(u -> u.path("/v2/local/search/keyword.json")
                            .queryParam("query", "{q}").queryParam("size", Math.max(1, Math.min(size, 15)))
                            .build(query))
                    .retrieve().body(KeywordResponse.class);
            return r == null || r.documents() == null ? List.of() : r.documents();
        } catch (RestClientResponseException e) {
            throw new ExternalApiException("카카오 로컬 API 오류 (" + e.getStatusCode().value() + ")", true);
        } catch (RestClientException e) {
            throw new ExternalApiException("카카오 로컬 API에 연결하지 못했어요.", true);
        }
    }
}

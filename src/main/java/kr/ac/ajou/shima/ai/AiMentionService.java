package kr.ac.ajou.shima.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.ac.ajou.shima.diagnosis.DiagnosisService;
import kr.ac.ajou.shima.diagnosis.ExternalApiException;
import kr.ac.ajou.shima.diagnosis.NaverSearchClient;
import kr.ac.ajou.shima.domain.AiMentionCheck;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.dto.AiMentionResponse;
import kr.ac.ajou.shima.dto.DiagnosisResponse;
import kr.ac.ajou.shima.repository.AiMentionCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * AI 언급률 (AEO 실측, 참고 지표) — 웹 검색이 되는 AI에게 손님처럼 "동네 + 업종 추천"을 물어보고
 * 답변에 우리 가게 이름이 나오는지 센다.
 *  - 같은 질문에도 답이 매번 달라서 쉬마 지수(규칙 점수)에는 넣지 않는다
 *  - 네이버 AI 브리핑은 공개 API가 없어 측정 불가 → 웹 검색 AI(Claude) 기준
 *  - 비용: 질문 3개 × (웹 검색 최대 2회 + 토큰) ≈ 회당 수백 원 → 사장님당 하루 3회 제한(컨트롤러)
 */
@Service
public class AiMentionService {

    private static final String SYSTEM = """
            당신은 한국어로 답하는 AI 어시스턴트입니다.
            사용자가 동네 가게를 추천해 달라고 하면, 웹 검색으로 확인한 실제 가게 이름을 들어 답하세요.
            가게 이름은 정확히 쓰고, 5곳 이내로 한 줄씩 이유를 붙여 짧게 답하세요.""";

    private final ClaudeClient claude;
    private final DiagnosisService diagnosisService;
    private final AiMentionCheckRepository repo;
    private final ObjectMapper objectMapper;

    public AiMentionService(ClaudeClient claude, DiagnosisService diagnosisService,
                            AiMentionCheckRepository repo, ObjectMapper objectMapper) {
        this.claude = claude;
        this.diagnosisService = diagnosisService;
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 손님이 할 법한 질문 3개 — 진단에서 쓴 "동네 + 업종" 검색어 기준 */
    static List<String> questions(String keyword) {
        return List.of(
                keyword + " 추천해 줘",
                keyword + " 중에 분위기 좋은 곳 알려 줘",
                keyword + " 처음 가 보려는데 어디가 좋아?");
    }

    /** 트랜잭션을 걸지 않음 — AI 응답을 기다리는 수십 초 동안 DB 연결을 잡고 있지 않도록 (조회·저장은 각자 짧게) */
    public AiMentionResponse check(Store store) {
        if (!claude.isConfigured()) {
            throw new ExternalApiException("Claude API 키가 설정되지 않았어요. (ANTHROPIC_API_KEY)", false);
        }
        DiagnosisResponse diag = diagnosisService.latest(store)
                .orElseThrow(() -> new IllegalArgumentException("먼저 온라인 진단을 한 번 실행해 주세요."));
        if (diag.searchKeyword() == null) {
            throw new IllegalArgumentException("진단에서 가게를 찾지 못해 질문을 만들 수 없어요. 상호명·지역을 확인해 다시 진단해 주세요.");
        }

        List<String> names = new ArrayList<>();
        names.add(NaverSearchClient.norm(store.getName()));
        if (diag.placeTitle() != null) names.add(NaverSearchClient.norm(diag.placeTitle()));

        List<String> qs = questions(diag.searchKeyword());
        ExecutorService pool = Executors.newFixedThreadPool(qs.size()); // 질문 3개 동시에 (총 대기 ≈ 가장 느린 1개)
        try {
            List<Future<ClaudeClient.Answer>> futures = new ArrayList<>();
            for (String q : qs) futures.add(pool.submit(() -> claude.askWithWebSearch(SYSTEM, q, 2)));

            List<AiMentionResponse.Item> items = new ArrayList<>();
            int mentioned = 0;
            for (int i = 0; i < qs.size(); i++) {
                ClaudeClient.Answer a = await(futures.get(i));
                String norm = NaverSearchClient.norm(a.text());
                boolean hit = names.stream().anyMatch(n -> !n.isEmpty() && norm.contains(n));
                if (hit) mentioned++;
                String excerpt = a.text().length() > 400 ? a.text().substring(0, 400) + "…" : a.text();
                items.add(new AiMentionResponse.Item(qs.get(i), hit, excerpt, a.webSearches()));
            }

            AiMentionResponse res = new AiMentionResponse(java.time.Instant.now(), claude.model(), qs.size(), mentioned, items);
            repo.save(new AiMentionCheck(store, qs.size(), mentioned, claude.model(), toJson(res)));
            return res;
        } finally {
            pool.shutdownNow();
        }
    }

    @Transactional(readOnly = true)
    public Optional<AiMentionResponse> latest(Store store) {
        return repo.findFirstByStoreOrderByCheckedAtDesc(store).map(c -> fromJson(c.getDetailJson()));
    }

    private static ClaudeClient.Answer await(Future<ClaudeClient.Answer> f) {
        try {
            return f.get(150, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ExternalApiException x) throw x;
            throw new ExternalApiException("AI 확인 중 오류가 났어요.", true);
        } catch (TimeoutException e) {
            throw new ExternalApiException("AI 응답이 너무 오래 걸려요. 잠시 후 다시 해 주세요.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException("AI 확인이 중단됐어요.", true);
        }
    }

    private String toJson(AiMentionResponse r) {
        try { return objectMapper.writeValueAsString(r); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private AiMentionResponse fromJson(String s) {
        try { return objectMapper.readValue(s, AiMentionResponse.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}

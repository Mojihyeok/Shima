package kr.ac.ajou.shima.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.ac.ajou.shima.auth.CurrentOwner;
import kr.ac.ajou.shima.diagnosis.DiagnosisService;
import kr.ac.ajou.shima.diagnosis.ExternalApiException;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.dto.WriteRequest;
import kr.ac.ajou.shima.dto.WriteResponse;
import kr.ac.ajou.shima.repository.RewardRuleRepository;
import kr.ac.ajou.shima.repository.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * SNS 글 대신 쓰기 — 글쓰기가 귀찮은 사장님을 위해 버튼 2번 + 한 줄 메모로 인스타 캡션/블로그 글을 완성.
 *  - 사실은 사장님 메모·매장 정보·도장 규칙에서만 (메뉴·가격·후기를 지어내지 않음)
 *  - 진단의 "동네 + 업종" 검색어를 자연스럽게 넣어 검색 노출(SEO)·AI 노출 준비도(블로그 설명 풍부함)에 도움
 *  - AI 실패 시 기본 문구로 대체. 저장하지 않음 — "올렸어요"는 화면에서 마케팅 기록 API로 남김
 */
@Service
public class WriterService {

    private static final Logger log = LoggerFactory.getLogger(WriterService.class);

    /** 주제 — 사장님 앱 write.ts와 맞출 것 */
    static final Map<String, String> TOPICS = new LinkedHashMap<>();
    static {
        TOPICS.put("today", "오늘의 메뉴·오늘 가게 소식");
        TOPICS.put("new_menu", "새로 나온 메뉴 소개");
        TOPICS.put("mood", "가게 분위기·자리 소개 (공부·모임·휴식)");
        TOPICS.put("thanks", "찾아와 주시는 손님·단골께 감사 인사");
        TOPICS.put("stamp", "도장 적립 혜택 안내");
        TOPICS.put("free", "사장님 메모 그대로 자유 주제");
    }

    private static final String SYSTEM = """
            너는 동네 카페·식당 사장님 대신 SNS 글을 써 주는 도우미다. 사장님은 글쓰기를 어려워하니,
            그대로 복사해서 올릴 수 있는 완성된 글을 쓴다. 사장님 본인이 쓰는 1인칭("저희 가게", "오늘은")으로.

            [사장님 가게 이야기]
            - ownerGoal(고민·목표)과 storeNotes(가게 특징·손님층)가 있으면 글의 방향과 말투를 거기에 맞춘다.
              (예: 손님층이 교직원·간호사면 "퇴근길", "근무 중 휴식"처럼 그분들께 와닿는 표현, 저녁 손님이 목표면 저녁 방문 권유)
            - storeNotes에 적힌 사실(골목 안, 조용함, 단골이 많음 등)은 글에 써도 된다.

            [사실]
            - 입력의 store, storeNotes, stampReward, note에 있는 사실만 쓴다. 메뉴 이름·가격·재료·원두·수상·손님 후기·할인을 지어내지 않는다.
            - note가 비어 있으면 구체적 메뉴 대신 분위기·인사·방문 권유 위주로 쓴다.
            - "맛집", "최고", "1등", "유명한" 같은 자기 자랑 금지. 과장 금지.

            [검색에 잘 걸리게]
            - searchKeyword(예: "우만동 카페")와 가게 이름을 본문에 자연스럽게 1~2번 넣는다.
            - 블로그는 위치(동네), 분위기, 메뉴(note에 있을 때), 이용 팁(좌석·공부·모임 등 일반적인 것) 중 2가지 이상을 설명한다.

            [형식]
            - instagram: title은 "", body는 3~6줄 캡션(250자 이내, 줄바꿈 \\n), 이모지 2~4개, hashtags 8~12개.
            - blog: title 30자 이내, body는 600~900자, 소제목 2~3개("■ 소제목" 형태)와 짧은 문단, 마지막에 방문 안내 한 줄, hashtags 5~8개.
            - hashtags: '#' 없이 단어만, 띄어쓰기 없이. 가게 이름·동네+업종·주제 관련 위주.

            출력은 JSON 객체 하나만 (설명·코드블록 없이):
            {"title":"","body":"","hashtags":["",""]}""";

    private final GeminiClient ai;
    private final StoreRepository storeRepository;
    private final RewardRuleRepository ruleRepo;
    private final DiagnosisService diagnosisService;
    private final ObjectMapper om;

    public WriterService(GeminiClient ai, StoreRepository storeRepository, RewardRuleRepository ruleRepo,
                         DiagnosisService diagnosisService, ObjectMapper om) {
        this.ai = ai;
        this.storeRepository = storeRepository;
        this.ruleRepo = ruleRepo;
        this.diagnosisService = diagnosisService;
        this.om = om;
    }

    public WriteResponse write(WriteRequest req) {
        String channel = req.channel().trim().toLowerCase();
        if (!channel.equals("instagram") && !channel.equals("blog")) throw new IllegalArgumentException("인스타 또는 블로그를 골라 주세요.");
        String topic = req.topic().trim();
        if (!TOPICS.containsKey(topic)) throw new IllegalArgumentException("알 수 없는 주제예요.");
        String note = req.note() == null ? "" : req.note().trim();
        if (topic.equals("free") && note.isEmpty()) throw new IllegalArgumentException("자유 주제는 한 줄 메모를 적어 주세요.");

        Store store = storeRepository.findFirstByOwner_Id(CurrentOwner.id())
                .orElseThrow(() -> new IllegalStateException("연결된 매장이 없습니다."));
        String keyword = diagnosisService.latest(store).map(d -> d.searchKeyword()).orElse(null);
        String reward = ruleRepo.findFirstByStoreAndActiveTrue(store)
                .map(r -> "도장 " + r.getStampGoal() + "개 모으면 " + r.getRewardName()).orElse(null);

        if (ai.isConfigured()) {
            ObjectNode in = om.createObjectNode();
            in.put("channel", channel);
            in.put("topic", TOPICS.get(topic));
            in.put("note", note);
            ObjectNode s = in.putObject("store");
            s.put("name", store.getName());
            s.put("region", store.getRegion());
            in.put("searchKeyword", keyword);
            in.put("stampReward", reward);
            in.put("ownerGoal", store.getOwnerGoal());
            in.put("storeNotes", store.getStoreNotes());
            try {
                GeminiClient.Answer a = ai.ask(SYSTEM, in.toPrettyString(), 3000, "low");
                WriteResponse r = parse(a.text(), channel, topic);
                if (r != null) return r;
                log.warn(">> [writer] AI 응답 해석 실패 → 기본 문구");
            } catch (ExternalApiException e) {
                log.warn(">> [writer] AI 호출 실패 → 기본 문구: {}", e.getMessage());
            }
        }
        return template(channel, topic, note, store.getName(), keyword, reward);
    }

    private WriteResponse parse(String text, String channel, String topic) {
        if (text == null) return null;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode n = om.readTree(text.substring(a, b + 1));
            String body = n.path("body").asText("").trim();
            if (body.isEmpty()) return null;
            String title = channel.equals("blog") ? cut(n.path("title").asText(""), 60) : "";
            List<String> tags = new ArrayList<>();
            for (JsonNode t : n.path("hashtags")) {
                String v = t.asText("").replace("#", "").replaceAll("\\s+", "");
                if (!v.isEmpty() && !tags.contains(v)) tags.add(v);
                if (tags.size() >= 12) break;
            }
            return new WriteResponse(channel, topic, title, cut(body, channel.equals("blog") ? 2000 : 600), tags, "AI");
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String cut(String s, int len) {
        String t = s == null ? "" : s.trim();
        return t.length() > len ? t.substring(0, len) : t;
    }

    /** AI를 못 쓸 때 기본 문구 — 사장님 메모를 그대로 넣음 */
    private static WriteResponse template(String channel, String topic, String note, String name, String keyword, String reward) {
        String place = keyword == null ? name : keyword.split(" ")[0] + " " + name;
        String memo = note.isEmpty() ? "" : note + "\n";
        String line = switch (topic) {
            case "new_menu" -> "새로 나온 메뉴를 소개해요.";
            case "mood" -> "편하게 쉬어 가기 좋은 자리가 준비되어 있어요.";
            case "thanks" -> "늘 찾아와 주셔서 감사합니다.";
            case "stamp" -> reward == null ? "도장을 모아 보세요." : reward + "을 드려요. 계산대 QR만 찍으면 돼요.";
            default -> "오늘도 문 열었어요.";
        };
        List<String> tags = new ArrayList<>(List.of(name.replaceAll("\\s+", "")));
        if (keyword != null) tags.add(keyword.replaceAll("\\s+", ""));
        tags.add("동네카페");
        if (channel.equals("instagram")) {
            return new WriteResponse(channel, topic, "", memo + line + " ☕\n" + place + "에서 기다릴게요.", tags, "RULE");
        }
        String body = "■ " + name + " 소식\n" + memo + line + "\n\n■ 찾아오시는 길\n" + place + "에 있어요. 편하게 들러 주세요.";
        return new WriteResponse(channel, topic, name + " 소식", body, tags, "RULE");
    }
}

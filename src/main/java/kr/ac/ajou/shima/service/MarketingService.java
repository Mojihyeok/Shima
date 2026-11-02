package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.auth.CurrentOwner;
import kr.ac.ajou.shima.domain.ActionSource;
import kr.ac.ajou.shima.domain.MarketingAction;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.dto.MarketingActionRequest;
import kr.ac.ajou.shima.dto.MarketingActionResponse;
import kr.ac.ajou.shima.repository.MarketingActionRepository;
import kr.ac.ajou.shima.repository.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 마케팅 실행 기록 — "언제 무엇을 했는지"를 남겨 방문 그래프 위에 겹쳐 보여준다 (F5 오버레이).
 * 표현 원칙: "인스타 올린 주에 손님이 늘었다"(같은 시기)까지만. 인과("인스타 때문에")는 주장하지 않음.
 */
@Service
public class MarketingService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 기록 종류 — 사장님 앱 ACTION_LABEL과 맞출 것 */
    public static final Set<String> TYPES = Set.of(
            "instagram_post",   // 인스타 게시물
            "blog_review",      // 블로그·체험단
            "message",          // 문자·카톡 안내
            "event",            // 이벤트·할인
            "naver_place",      // 네이버 플레이스 정보 수정
            "flyer",            // 전단·현수막
            "other");

    private final MarketingActionRepository repo;
    private final StoreRepository storeRepository;

    public MarketingService(MarketingActionRepository repo, StoreRepository storeRepository) {
        this.repo = repo;
        this.storeRepository = storeRepository;
    }

    private Store currentStore() {
        return storeRepository.findFirstByOwner_Id(CurrentOwner.id())
                .orElseThrow(() -> new IllegalStateException("연결된 매장이 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<MarketingActionResponse> list(LocalDate fromReq, LocalDate toReq) {
        LocalDate today = LocalDate.now(KST);
        LocalDate to = toReq == null ? today : toReq;
        LocalDate from = fromReq == null ? to.minusWeeks(26) : fromReq; // 기본: 최근 26주 (그래프 최대 범위)
        Instant start = from.atStartOfDay(KST).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(KST).toInstant();
        return repo.findByStoreAndActedAtBetweenOrderByActedAtAsc(currentStore(), start, end).stream()
                .filter(a -> a.getActedAt().isBefore(end))
                .map(MarketingService::toResponse)
                .toList();
    }

    @Transactional
    public MarketingActionResponse add(MarketingActionRequest req) {
        String type = req.actionType().trim();
        if (!TYPES.contains(type)) throw new IllegalArgumentException("알 수 없는 기록 종류예요.");
        LocalDate today = LocalDate.now(KST);
        LocalDate date = req.date() == null ? today : req.date();
        if (date.isAfter(today)) throw new IllegalArgumentException("미래 날짜는 기록할 수 없어요.");
        if (date.isBefore(today.minusYears(1))) throw new IllegalArgumentException("1년 이내 날짜만 기록할 수 있어요.");

        // 오늘이면 지금 시각, 지난 날짜면 그날 정오로 (날짜 단위로만 쓰므로 시각은 의미 없음)
        Instant at = date.equals(today) ? Instant.now() : date.atTime(LocalTime.NOON).atZone(KST).toInstant();
        String memo = req.memo() == null || req.memo().isBlank() ? null : req.memo().trim();
        return toResponse(repo.save(new MarketingAction(currentStore(), type, ActionSource.MANUAL, at, memo)));
    }

    @Transactional
    public void delete(Long id) {
        MarketingAction a = repo.findByIdAndStore(id, currentStore())
                .orElseThrow(() -> new IllegalArgumentException("기록을 찾을 수 없어요."));
        repo.delete(a);
    }

    private static MarketingActionResponse toResponse(MarketingAction a) {
        return new MarketingActionResponse(a.getId(), a.getActionType(), a.getSource().name(),
                a.getActedAt().atZone(KST).toLocalDate(), a.getMemo());
    }
}

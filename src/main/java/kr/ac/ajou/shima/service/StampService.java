package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.dto.LandingResponse;
import kr.ac.ajou.shima.dto.StampRequest;
import kr.ac.ajou.shima.dto.StampResponse;
import kr.ac.ajou.shima.repository.*;
import kr.ac.ajou.shima.dto.WithdrawRequest;
import kr.ac.ajou.shima.ratelimit.RateLimiter;
import kr.ac.ajou.shima.support.PhoneHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

@Service
public class StampService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 휴대폰 번호: 010/011/016/017/018/019 + 7~8자리 */
    private static final Pattern MOBILE = Pattern.compile("^01[016789]\\d{7,8}$");

    private final QrTokenRepository qrTokenRepository;
    private final CustomerRepository customerRepository;
    private final VisitLogRepository visitLogRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final RewardGrantRepository rewardGrantRepository;
    private final PhoneHasher phoneHasher;
    private final CustomerDataService customerDataService;
    private final RateLimiter rateLimiter;
    private final String consentVersion;

    public StampService(QrTokenRepository qrTokenRepository,
                        CustomerRepository customerRepository,
                        VisitLogRepository visitLogRepository,
                        RewardRuleRepository rewardRuleRepository,
                        RewardGrantRepository rewardGrantRepository,
                        PhoneHasher phoneHasher,
                        CustomerDataService customerDataService,
                        RateLimiter rateLimiter,
                        @Value("${shima.privacy.consent-version}") String consentVersion) {
        this.qrTokenRepository = qrTokenRepository;
        this.customerRepository = customerRepository;
        this.visitLogRepository = visitLogRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardGrantRepository = rewardGrantRepository;
        this.phoneHasher = phoneHasher;
        this.customerDataService = customerDataService;
        this.rateLimiter = rateLimiter;
        this.consentVersion = consentVersion;
    }

    private static String normalizePhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (!MOBILE.matcher(digits).matches()) {
            throw new IllegalArgumentException("휴대폰 번호를 다시 확인해 주세요.");
        }
        return digits;
    }

    /** QR 토큰 검증 (만료/무효면 예외) */
    private QrToken resolveToken(String token) {
        QrToken qr = qrTokenRepository.findByToken(token)
                .orElseThrow(() -> new TokenInvalidException("무효한 QR입니다."));
        if (Instant.now().isAfter(qr.getExpiresAt())) {
            throw new TokenExpiredException("QR이 만료되었어요. 매장 화면의 QR을 다시 찍어 주세요.");
        }
        return qr;
    }

    @Transactional(readOnly = true)
    public LandingResponse landing(String token) {
        Store store = resolveToken(token).getStore();
        RewardRule rule = rewardRuleRepository.findFirstByStoreAndActiveTrue(store).orElse(null);
        return new LandingResponse(
                store.getName(),
                rule != null ? rule.getStampGoal() : 0,
                rule != null ? rule.getRewardName() : null
        );
    }

    @Transactional
    public StampResponse stamp(String token, StampRequest req) {
        QrToken qr = resolveToken(token);
        Store store = qr.getStore();

        String digits = normalizePhone(req.phone());
        String hash = phoneHasher.hash(store.getId(), digits);
        rateLimiter.hit("stamp-phone:" + store.getId() + ":" + hash, 5, Duration.ofMinutes(10),
                "잠시 후 다시 적립해 주세요.");

        // 동의 기록이 없으면(처음 온 손님 포함) 동의를 먼저 받는다 — 동의 전에는 아무것도 저장하지 않음
        Customer existing = customerRepository.findByStoreAndPhoneHash(store, hash).orElse(null);
        boolean agreedNow = Boolean.TRUE.equals(req.agreed());
        if ((existing == null || !existing.hasConsent()) && !agreedNow) {
            throw new ConsentRequiredException("처음 적립하실 때 개인정보 수집·이용 동의가 필요해요.");
        }
        Customer customer = existing != null ? existing
                : customerRepository.save(new Customer(store, hash, phoneHasher.last4(digits)));
        if (!customer.hasConsent()) customer.agree(consentVersion, Instant.now());

        // 선택 정보 갱신 (있을 때만)
        if (req.name() != null && !req.name().isBlank()) customer.setName(req.name());
        if (req.birthday() != null && !req.birthday().isBlank()) customer.setBirthday(req.birthday());

        RewardRule rule = rewardRuleRepository.findFirstByStoreAndActiveTrue(store).orElse(null);
        int goal = rule != null ? rule.getStampGoal() : 0;

        // 오늘 이미 적립? — 차단하지 않고 현재 도장 수 그대로 반환
        Instant startOfToday = LocalDate.now(KST).atStartOfDay(KST).toInstant();
        if (visitLogRepository.existsByCustomerAndVisitedAtGreaterThanEqual(customer, startOfToday)) {
            int stamps = (int) visitLogRepository.countByCustomer(customer);
            return buildResponse(store, rule, goal, stamps, false, true);
        }

        boolean isNew = (customer.getFirstVisitAt() == null);
        Instant now = Instant.now();
        visitLogRepository.save(new VisitLog(store, customer, now, VisitSource.QR, isNew, qr.getId()));
        if (isNew) customer.setFirstVisitAt(now);

        int stamps = (int) visitLogRepository.countByCustomer(customer);

        // 리워드 판정 — 목표 배수 도달 시 자동 지급
        boolean reached = false;
        if (goal > 0 && stamps % goal == 0) {
            rewardGrantRepository.save(new RewardGrant(store, customer, rule, GrantType.AUTO, null));
            reached = true;
        }
        return buildResponse(store, rule, goal, stamps, reached, false);
    }

    /**
     * 손님 본인 정보 삭제 (QR 화면에서 전화번호 입력).
     * 등록 여부를 알려주지 않도록 결과는 항상 같은 응답 — 남의 번호 가입 여부 조회 방지
     */
    @Transactional
    public void withdraw(String token, WithdrawRequest req) {
        Store store = resolveToken(token).getStore();
        String digits = normalizePhone(req.phone());
        String hash = phoneHasher.hash(store.getId(), digits);
        rateLimiter.hit("withdraw-phone:" + store.getId() + ":" + hash, 3, Duration.ofDays(1),
                "오늘은 더 삭제 요청을 할 수 없어요. 매장에 직접 요청해 주세요.");
        customerRepository.findByStoreAndPhoneHash(store, hash)
                .ifPresent(c -> customerDataService.delete(c, "customer"));
    }

    private StampResponse buildResponse(Store store, RewardRule rule, int goal,
                                        int stamps, boolean reached, boolean alreadyToday) {
        int remaining = goal > 0 ? (goal - (stamps % goal)) % goal : 0;
        return new StampResponse(
                store.getName(),
                stamps,
                goal,
                remaining,
                reached,
                rule != null ? rule.getRewardName() : null,
                alreadyToday
        );
    }

    // --- 도메인 예외 ---
    public static class TokenInvalidException extends RuntimeException {
        public TokenInvalidException(String m) { super(m); }
    }
    public static class TokenExpiredException extends RuntimeException {
        public TokenExpiredException(String m) { super(m); }
    }
    public static class ConsentRequiredException extends RuntimeException {
        public ConsentRequiredException(String m) { super(m); }
    }
}

package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.dto.LandingResponse;
import kr.ac.ajou.shima.dto.StampRequest;
import kr.ac.ajou.shima.dto.StampResponse;
import kr.ac.ajou.shima.repository.*;
import kr.ac.ajou.shima.support.PhoneHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class StampService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final QrTokenRepository qrTokenRepository;
    private final CustomerRepository customerRepository;
    private final VisitLogRepository visitLogRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final RewardGrantRepository rewardGrantRepository;
    private final PhoneHasher phoneHasher;

    public StampService(QrTokenRepository qrTokenRepository,
                        CustomerRepository customerRepository,
                        VisitLogRepository visitLogRepository,
                        RewardRuleRepository rewardRuleRepository,
                        RewardGrantRepository rewardGrantRepository,
                        PhoneHasher phoneHasher) {
        this.qrTokenRepository = qrTokenRepository;
        this.customerRepository = customerRepository;
        this.visitLogRepository = visitLogRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardGrantRepository = rewardGrantRepository;
        this.phoneHasher = phoneHasher;
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

        String digits = req.phone().replaceAll("\\D", "");
        String hash = phoneHasher.hash(store.getId(), digits);

        Customer customer = customerRepository.findByStoreAndPhoneHash(store, hash)
                .orElseGet(() -> customerRepository.save(
                        new Customer(store, hash, phoneHasher.last4(digits))));

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
}

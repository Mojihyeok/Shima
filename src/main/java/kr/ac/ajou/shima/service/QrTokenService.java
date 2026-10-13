package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.QrKind;
import kr.ac.ajou.shima.domain.QrToken;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.repository.QrTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * QR 토큰 발급.
 * - ROTATING: 유효시간(기본 10분)의 절반이 지나면 새 토큰으로 교체.
 *   이전 토큰은 만료 전까지 유효 → 교체 직전에 찍은 손님도 적립 가능.
 * - PRINT: 1년짜리 장기 토큰 (인쇄물용, 태블릿 없을 때 대비)
 * 별도 스케줄러 없이 사장님 화면이 조회할 때 lazy하게 교체한다.
 */
@Service
public class QrTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final QrTokenRepository repo;
    private final Duration ttl;

    public QrTokenService(QrTokenRepository repo,
                          @Value("${shima.qr-token-ttl-minutes}") long ttlMinutes) {
        this.repo = repo;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Transactional
    public QrToken currentRotating(Store store) {
        Instant now = Instant.now();
        return repo.findFirstByStoreAndKindAndExpiresAtAfterOrderByExpiresAtDesc(store, QrKind.ROTATING, now)
                .filter(t -> Duration.between(now, t.getExpiresAt()).compareTo(ttl.dividedBy(2)) > 0)
                .orElseGet(() -> repo.save(new QrToken(store, newToken(), now.plus(ttl), QrKind.ROTATING)));
    }

    @Transactional
    public QrToken printToken(Store store) {
        Instant now = Instant.now();
        return repo.findFirstByStoreAndKindAndExpiresAtAfterOrderByExpiresAtDesc(store, QrKind.PRINT, now)
                .orElseGet(() -> repo.save(new QrToken(store, newToken(), now.plus(365, ChronoUnit.DAYS), QrKind.PRINT)));
    }

    /** URL-safe 랜덤 토큰 (128bit) */
    private String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

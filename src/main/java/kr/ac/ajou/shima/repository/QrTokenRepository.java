package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.QrKind;
import kr.ac.ajou.shima.domain.QrToken;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface QrTokenRepository extends JpaRepository<QrToken, Long> {
    Optional<QrToken> findByToken(String token);

    /** 해당 종류의 유효 토큰 중 가장 늦게 만료되는 것 */
    Optional<QrToken> findFirstByStoreAndKindAndExpiresAtAfterOrderByExpiresAtDesc(
            Store store, QrKind kind, Instant now);
}

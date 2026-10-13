package kr.ac.ajou.shima.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** 사장님 액세스 토큰 발급/검증 (HS256, subject = ownerId) */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final Duration ttl;

    public JwtProvider(@Value("${shima.jwt.secret}") String secret,
                       @Value("${shima.jwt.ttl-hours}") long ttlHours) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("shima.jwt.secret은 32바이트 이상이어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String issue(Long ownerId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(ownerId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 유효하면 ownerId, 위조·만료·형식 오류면 null */
    public Long verify(String token) {
        try {
            String sub = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload().getSubject();
            return Long.valueOf(sub);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}

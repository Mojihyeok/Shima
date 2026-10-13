package kr.ac.ajou.shima.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 전화번호 해시. hash = SHA-256(storeId + phone + salt).
 * 매장별 storeId를 섞어 매장 간 교차 식별을 막고, 원본 번호는 저장하지 않는다.
 */
@Component
public class PhoneHasher {

    private final String salt;

    public PhoneHasher(@Value("${shima.phone-hash-salt}") String salt) {
        this.salt = salt;
    }

    public String hash(Long storeId, String phoneDigitsOnly) {
        String input = storeId + ":" + phoneDigitsOnly + ":" + salt;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 표시용 뒷 4자리 */
    public String last4(String phoneDigitsOnly) {
        String d = phoneDigitsOnly.replaceAll("\\D", "");
        return d.length() <= 4 ? d : d.substring(d.length() - 4);
    }
}

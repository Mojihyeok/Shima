package kr.ac.ajou.shima.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청한 사람의 IP. Render 같은 호스팅은 프록시 뒤라 X-Forwarded-For의 첫 값이 실제 IP.
 * (이 헤더는 손님 쪽에서 조작할 수 있어 완벽하지 않음 — 아이디·번호별 제한을 함께 둠)
 */
public final class ClientIp {
    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}

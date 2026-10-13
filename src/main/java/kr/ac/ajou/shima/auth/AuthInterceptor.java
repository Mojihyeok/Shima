package kr.ac.ajou.shima.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/** /api/v1/owner/** 보호 — Authorization: Bearer {jwt} 검증 후 ownerId를 요청 속성에 저장 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String OWNER_ID_ATTR = "shima.ownerId";

    private final JwtProvider jwtProvider;

    public AuthInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (CorsUtils.isPreFlightRequest(req)) return true; // CORS 사전요청은 통과

        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        Long ownerId = (header != null && header.startsWith("Bearer "))
                ? jwtProvider.verify(header.substring(7))
                : null;

        if (ownerId == null) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"로그인이 필요해요.\"}");
            return false;
        }
        req.setAttribute(OWNER_ID_ATTR, ownerId);
        return true;
    }
}

package kr.ac.ajou.shima.auth;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** 현재 요청의 로그인 사장님 id (AuthInterceptor가 넣어둔 값) */
public final class CurrentOwner {

    private CurrentOwner() {}

    public static Long id() {
        Object v = RequestContextHolder.currentRequestAttributes()
                .getAttribute(AuthInterceptor.OWNER_ID_ATTR, RequestAttributes.SCOPE_REQUEST);
        if (v == null) throw new UnauthorizedException("로그인이 필요해요.");
        return (Long) v;
    }
}

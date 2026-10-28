package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.ratelimit.RateLimiter;
import java.time.Duration;

import kr.ac.ajou.shima.auth.CurrentOwner;
import kr.ac.ajou.shima.auth.JwtProvider;
import kr.ac.ajou.shima.auth.UnauthorizedException;
import kr.ac.ajou.shima.domain.OwnerUser;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.UiMode;
import kr.ac.ajou.shima.dto.LoginRequest;
import kr.ac.ajou.shima.dto.LoginResponse;
import kr.ac.ajou.shima.dto.MeResponse;
import kr.ac.ajou.shima.repository.OwnerUserRepository;
import kr.ac.ajou.shima.repository.StoreRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final OwnerUserRepository ownerRepo;
    private final StoreRepository storeRepo;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RateLimiter rateLimiter;

    public AuthService(OwnerUserRepository ownerRepo, StoreRepository storeRepo,
                       PasswordEncoder encoder, JwtProvider jwt, RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.ownerRepo = ownerRepo;
        this.storeRepo = storeRepo;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        // 아이디가 없든 비번이 틀리든 같은 메시지 (계정 존재 여부 노출 방지)
        // 아이디별 실패 5번 → 15분 잠금 (비밀번호 대입 방지). 없는 아이디도 똑같이 세서 존재 여부를 숨김
        String failKey = "login-fail:" + req.loginId().trim().toLowerCase();
        rateLimiter.checkOnly(failKey, 5, Duration.ofMinutes(15),
                "비밀번호를 여러 번 틀려서 15분 동안 로그인할 수 없어요.");
        OwnerUser owner = ownerRepo.findByLoginId(req.loginId())
                .filter(o -> encoder.matches(req.password(), o.getPwHash()))
                .orElse(null);
        if (owner == null) {
            rateLimiter.record(failKey);
            throw new UnauthorizedException("아이디 또는 비밀번호가 맞지 않아요.");
        }
        rateLimiter.reset(failKey);
        Store store = storeOf(owner.getId());
        return new LoginResponse(jwt.issue(owner.getId()), store.getName(), owner.getUiMode().name());
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        OwnerUser owner = currentOwner();
        return new MeResponse(owner.getLoginId(), storeOf(owner.getId()).getName(), owner.getUiMode().name());
    }

    @Transactional
    public MeResponse updateUiMode(UiMode mode) {
        OwnerUser owner = currentOwner();
        owner.setUiMode(mode);
        return new MeResponse(owner.getLoginId(), storeOf(owner.getId()).getName(), mode.name());
    }

    private OwnerUser currentOwner() {
        return ownerRepo.findById(CurrentOwner.id())
                .orElseThrow(() -> new UnauthorizedException("계정을 찾을 수 없어요. 다시 로그인해 주세요."));
    }

    private Store storeOf(Long ownerId) {
        return storeRepo.findFirstByOwner_Id(ownerId)
                .orElseThrow(() -> new IllegalStateException("연결된 매장이 없습니다."));
    }
}

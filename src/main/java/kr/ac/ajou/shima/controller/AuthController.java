package kr.ac.ajou.shima.controller;

import jakarta.servlet.http.HttpServletRequest;
import kr.ac.ajou.shima.ratelimit.ClientIp;
import kr.ac.ajou.shima.ratelimit.RateLimiter;
import java.time.Duration;

import jakarta.validation.Valid;
import kr.ac.ajou.shima.dto.LoginRequest;
import kr.ac.ajou.shima.dto.LoginResponse;
import kr.ac.ajou.shima.dto.MeResponse;
import kr.ac.ajou.shima.dto.UiModeRequest;
import kr.ac.ajou.shima.service.AuthService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /** 로그인 (공개) */
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // 같은 곳에서 여러 아이디를 돌아가며 시도하는 것 방지 (아이디별 실패 잠금은 AuthService)
        rateLimiter.hit("login-ip:" + ClientIp.of(http), 20, Duration.ofMinutes(10),
                "로그인 시도가 너무 많아요. 잠시 후 다시 해 주세요.");
        return authService.login(request);
    }

    /** 내 정보 (보호됨 — /owner/**) */
    @GetMapping("/owner/me")
    public MeResponse me() {
        return authService.me();
    }

    /** 화면 모드 변경: EASY / STANDARD */
    @PatchMapping("/owner/me/ui-mode")
    public MeResponse updateUiMode(@Valid @RequestBody UiModeRequest request) {
        return authService.updateUiMode(request.uiMode());
    }
}

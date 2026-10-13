package kr.ac.ajou.shima.controller;

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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 로그인 (공개) */
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
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

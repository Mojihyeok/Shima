package kr.ac.ajou.shima.controller;

import jakarta.validation.Valid;
import kr.ac.ajou.shima.dto.LandingResponse;
import kr.ac.ajou.shima.dto.StampRequest;
import kr.ac.ajou.shima.dto.StampResponse;
import kr.ac.ajou.shima.service.StampService;
import org.springframework.web.bind.annotation.*;

/** 손님 적립 API — 인증 없이 QR 토큰으로만 접근 (회원가입 없음) */
@RestController
@RequestMapping("/api/v1/c")
public class CustomerStampController {

    private final StampService stampService;

    public CustomerStampController(StampService stampService) {
        this.stampService = stampService;
    }

    /** 적립 페이지 진입 — 매장명/리워드 정보 */
    @GetMapping("/{token}")
    public LandingResponse landing(@PathVariable String token) {
        return stampService.landing(token);
    }

    /** 적립 */
    @PostMapping("/{token}/stamp")
    public StampResponse stamp(@PathVariable String token,
                               @Valid @RequestBody StampRequest request) {
        return stampService.stamp(token, request);
    }
}

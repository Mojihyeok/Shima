package kr.ac.ajou.shima.controller;

import jakarta.validation.Valid;
import kr.ac.ajou.shima.dto.LandingResponse;
import kr.ac.ajou.shima.dto.StampRequest;
import kr.ac.ajou.shima.dto.StampResponse;
import kr.ac.ajou.shima.dto.WithdrawRequest;
import kr.ac.ajou.shima.service.StampService;
import jakarta.servlet.http.HttpServletRequest;
import kr.ac.ajou.shima.ratelimit.ClientIp;
import kr.ac.ajou.shima.ratelimit.RateLimiter;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/** 손님 적립 API — 인증 없이 QR 토큰으로만 접근 (회원가입 없음) */
@RestController
@RequestMapping("/api/v1/c")
public class CustomerStampController {

    private final StampService stampService;
    private final RateLimiter rateLimiter;

    public CustomerStampController(StampService stampService, RateLimiter rateLimiter) {
        this.stampService = stampService;
        this.rateLimiter = rateLimiter;
    }

    /** 적립 페이지 진입 — 매장명/리워드 정보 */
    @GetMapping("/{token}")
    public LandingResponse landing(@PathVariable String token) {
        return stampService.landing(token);
    }

    /** 적립 */
    @PostMapping("/{token}/stamp")
    public StampResponse stamp(@PathVariable String token,
                               @Valid @RequestBody StampRequest request, HttpServletRequest http) {
        // 매장 와이파이는 손님들이 같은 IP를 쓰므로 넉넉하게 (번호별 제한은 StampService)
        rateLimiter.hit("stamp-ip:" + ClientIp.of(http), 60, Duration.ofMinutes(10),
                "잠시 후 다시 적립해 주세요.");
        return stampService.stamp(token, request);
    }

    /** 손님 본인 정보 삭제 — 등록 여부와 관계없이 같은 응답 */
    @PostMapping("/{token}/withdraw")
    public java.util.Map<String, Object> withdraw(@PathVariable String token,
                                                  @Valid @RequestBody WithdrawRequest request,
                                                  HttpServletRequest http) {
        rateLimiter.hit("withdraw-ip:" + ClientIp.of(http), 5, Duration.ofHours(1),
                "삭제 요청이 너무 많아요. 매장에 직접 요청해 주세요.");
        stampService.withdraw(token, request);
        return java.util.Map.of("done", true,
                "message", "등록된 정보가 있었다면 모두 삭제했어요. 도장 기록도 함께 사라져요.");
    }
}

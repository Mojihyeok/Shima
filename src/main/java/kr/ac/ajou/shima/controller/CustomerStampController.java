package kr.ac.ajou.shima.controller;

import jakarta.validation.Valid;
import kr.ac.ajou.shima.dto.LandingResponse;
import kr.ac.ajou.shima.dto.StampRequest;
import kr.ac.ajou.shima.dto.StampResponse;
import kr.ac.ajou.shima.dto.WithdrawRequest;
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

    /** 손님 본인 정보 삭제 — 등록 여부와 관계없이 같은 응답 */
    @PostMapping("/{token}/withdraw")
    public java.util.Map<String, Object> withdraw(@PathVariable String token,
                                                  @Valid @RequestBody WithdrawRequest request) {
        stampService.withdraw(token, request);
        return java.util.Map.of("done", true,
                "message", "등록된 정보가 있었다면 모두 삭제했어요. 도장 기록도 함께 사라져요.");
    }
}

package kr.ac.ajou.shima.dto;

/** QR 진입 시 적립 전 화면 정보 */
public record LandingResponse(
        String storeName,
        int stampGoal,
        String rewardName
) {}

package kr.ac.ajou.shima.dto;

/** 적립 응답 — 손님 화면에 크게 표시할 값들 */
public record StampResponse(
        String storeName,
        int stamps,                 // 현재 누적 도장 수
        int stampGoal,              // 리워드까지 필요한 도장 수
        int remaining,              // 다음 리워드까지 남은 횟수
        boolean rewardReached,      // 이번 적립으로 리워드 달성?
        String rewardName,
        boolean alreadyStampedToday, // 오늘 이미 적립함 (도장 수 그대로 반환)
        boolean firstVisit          // 이번이 첫 방문 → 손님 화면에서 유입 경로 질문
) {}

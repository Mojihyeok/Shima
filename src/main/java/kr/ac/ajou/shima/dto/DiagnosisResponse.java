package kr.ac.ajou.shima.dto;

import java.time.Instant;
import java.util.List;

/** 쉬마 브랜드 지수 결과 — 측정된 축만으로 100점 환산 */
public record DiagnosisResponse(
        Instant createdAt,
        int totalScore,             // 0~100 (측정된 축 합계 / 측정된 축 만점 × 100)
        int measuredMax,            // 측정된 축들의 만점 합 (100이면 5축 모두 측정)
        String placeTitle,          // 네이버 지역검색에서 찾은 가게 이름 (못 찾으면 null)
        String placeAddress,
        String searchKeyword,       // 노출 순위를 본 검색어 (예: "매탄동 카페")
        List<Axis> axes,
        Integer previousScore,      // 직전 진단 점수 (없으면 null)
        List<Peer> peers            // 같은 검색어 상위 가게와 비교 (우리 가게 포함, self=true). 예전 진단은 null
) {
    /**
     * 비교 대상 가게 — 블로그 언급량(추정)·최근 30일 새 글.
     * counted=false: 블로그에서 이름을 못 찾아(측정 실패 가능성) 중앙값 계산에서 뺀 가게
     */
    public record Peer(String name, long blogMentions, int recentPosts30, boolean self, boolean counted) {}

    public record Axis(
            String key,             // exposure | reputation | activity | completeness | revisit
            String name,            // PRD 축 이름: 찾기 쉬움 …
            String easyName,        // 쉬움 모드 문장: 손님이 찾기 쉬운 정도
            String techName,        // 표준 모드: 노출 …
            int points,
            int max,
            boolean measured,
            List<String> evidence   // 점수 근거 (사람이 읽는 문장)
    ) {}
}

package kr.ac.ajou.shima.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 서버 살아있는지 (Render 헬스체크용 — DB와 무관하게 가볍게) */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "shima-backend", "time", Instant.now().toString());
    }

    /** DB까지 확인 — UptimeRobot 등으로 주기 호출하면 서버와 DB(Supabase 7일 비활성 정지)를 함께 깨워둠 */
    @GetMapping("/health/db")
    public ResponseEntity<Map<String, Object>> healthDb() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "ok", "db", "ok", "time", Instant.now().toString()));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "degraded", "db", "unreachable"));
        }
    }
}

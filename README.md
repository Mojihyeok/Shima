# 쉬마 백엔드 (shima-backend)

Spring Boot 3.3 · Java 17 · Gradle

## 실행
```
./gradlew bootRun
```
- 기본 프로파일: `dev` (H2 인메모리 DB, 별도 설치 불필요)
- 헬스체크: http://localhost:8080/api/v1/health
- H2 콘솔: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:shima`)

## 프로파일
- `dev` — H2 인메모리, ddl-auto=update
- `prod` — PostgreSQL, 환경변수 `DB_URL / DB_USER / DB_PASSWORD` 필요

## 패키지 구조
- `domain` — JPA 엔티티 (다음 단계: 방문 로그 중심 스키마)
- `repository` — 데이터 접근
- `service` — 적립/집계/리포트 로직
- `controller` — REST API
- `config` — CORS 등 설정

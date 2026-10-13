package kr.ac.ajou.shima.config;

import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.repository.OwnerUserRepository;
import kr.ac.ajou.shima.repository.RewardRuleRepository;
import kr.ac.ajou.shima.repository.StoreRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영(prod) 사장님 계정 생성 — 회원가입 API가 없으므로 환경변수로 계정을 만든다.
 *
 * SHIMA_ACCOUNTS = "아이디|비밀번호|매장명|지역|도장목표|리워드|sample" 를 ; 로 여러 개
 *   예) demo|****|쉬마 데모카페|수원 영통구|10|아메리카노 1잔|sample;arosaegil|****|아로새길|수원
 *   - 도장목표·리워드 생략 시 10 / "아메리카노 1잔"
 *   - 7번째에 sample 이라고 쓰면 가짜 8주 이력 생성 (데모 매장 전용! 실매장 금지)
 * 이미 있는 아이디는 건드리지 않음(비밀번호 변경·덮어쓰기 없음) → 재시작해도 안전.
 * 계정이 만들어진 뒤에는 이 환경변수를 지워도 됨 (비밀번호가 평문으로 남지 않게 권장).
 */
@Profile("prod")
@Component
public class AccountBootstrap implements CommandLineRunner {

    private final OwnerUserRepository ownerRepo;
    private final StoreRepository storeRepo;
    private final RewardRuleRepository ruleRepo;
    private final PasswordEncoder encoder;
    private final SampleHistorySeeder sampleSeeder;
    private final String accounts;

    public AccountBootstrap(OwnerUserRepository ownerRepo, StoreRepository storeRepo, RewardRuleRepository ruleRepo,
                            PasswordEncoder encoder, SampleHistorySeeder sampleSeeder,
                            @Value("${SHIMA_ACCOUNTS:}") String accounts) {
        this.ownerRepo = ownerRepo;
        this.storeRepo = storeRepo;
        this.ruleRepo = ruleRepo;
        this.encoder = encoder;
        this.sampleSeeder = sampleSeeder;
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (accounts.isBlank()) return;
        for (String entry : accounts.split(";")) {
            String[] f = entry.trim().split("\\|", -1);
            if (f.length < 4 || f[0].isBlank() || f[1].isBlank() || f[2].isBlank()) {
                System.out.println(">> [accounts] 형식 오류로 건너뜀 (아이디|비밀번호|매장명|지역 필요)");
                continue;
            }
            String loginId = f[0].trim();
            if (ownerRepo.findByLoginId(loginId).isPresent()) {
                System.out.println(">> [accounts] 이미 있음: " + loginId);
                continue;
            }
            int goal = f.length > 4 && !f[4].isBlank() ? Integer.parseInt(f[4].trim()) : 10;
            String reward = f.length > 5 && !f[5].isBlank() ? f[5].trim() : "아메리카노 1잔";
            boolean sample = f.length > 6 && "sample".equalsIgnoreCase(f[6].trim());

            OwnerUser owner = ownerRepo.save(new OwnerUser(loginId, encoder.encode(f[1]), UiMode.STANDARD));
            Store store = storeRepo.save(new Store(f[2].trim(), f[3].trim(), owner));
            ruleRepo.save(new RewardRule(store, goal, reward));
            String extra = sample ? " · 샘플 이력 " + sampleSeeder.seed(store) + "건" : "";
            System.out.println(">> [accounts] 생성: " + loginId + " → " + store.getName() + extra);
        }
    }
}

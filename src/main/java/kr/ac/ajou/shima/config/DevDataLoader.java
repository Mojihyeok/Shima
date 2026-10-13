package kr.ac.ajou.shima.config;

import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * dev 프로파일 전용 시드 — 아로새길 매장 + 리워드 규칙 + 고정 QR 토큰(demo-token)
 * + (선택) 샘플 방문 이력. 끄려면 shima.dev.sample-history: false
 */
@Profile("dev")
@Component
public class DevDataLoader implements CommandLineRunner {

    private final OwnerUserRepository ownerRepo;
    private final StoreRepository storeRepo;
    private final RewardRuleRepository ruleRepo;
    private final QrTokenRepository qrRepo;
    private final PasswordEncoder encoder;
    private final SampleHistorySeeder sampleSeeder;
    private final boolean sampleHistory;

    public DevDataLoader(OwnerUserRepository ownerRepo, StoreRepository storeRepo,
                         RewardRuleRepository ruleRepo, QrTokenRepository qrRepo,
                         PasswordEncoder encoder, SampleHistorySeeder sampleSeeder,
                         @Value("${shima.dev.sample-history:true}") boolean sampleHistory) {
        this.ownerRepo = ownerRepo;
        this.storeRepo = storeRepo;
        this.ruleRepo = ruleRepo;
        this.qrRepo = qrRepo;
        this.encoder = encoder;
        this.sampleSeeder = sampleSeeder;
        this.sampleHistory = sampleHistory;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (storeRepo.count() > 0) return;

        OwnerUser owner = ownerRepo.save(new OwnerUser("arosaegil", encoder.encode("shima1234"), UiMode.STANDARD));
        Store store = storeRepo.save(new Store("아로새길", "수원", owner));
        ruleRepo.save(new RewardRule(store, 10, "아메리카노 1잔"));
        qrRepo.save(new QrToken(store, "demo-token", Instant.now().plus(365, ChronoUnit.DAYS), QrKind.PRINT));
        System.out.println(">> [dev seed] 아로새길 생성 완료. 사장님 로그인 arosaegil / shima1234, 적립 테스트 토큰: demo-token");

        if (sampleHistory) {
            int n = sampleSeeder.seed(store);
            System.out.println(">> [dev seed] 샘플 방문 이력 " + n + "건 생성 (가짜 손님 40명, 최근 8주)");
        }
    }
}

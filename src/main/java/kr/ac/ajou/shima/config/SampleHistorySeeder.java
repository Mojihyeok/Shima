package kr.ac.ajou.shima.config;

import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.repository.CustomerRepository;
import kr.ac.ajou.shima.repository.VisitLogRepository;
import kr.ac.ajou.shima.support.PhoneHasher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Random;

/**
 * 가짜 손님 40명의 최근 8주 방문 이력 (그래프·리포트 시연용).
 * ⚠️ 실매장(아로새길)에는 절대 쓰지 말 것 — dev 시드와 "데모 매장"에만 사용.
 * 오늘 방문은 만들지 않음 → '오늘 온 손님'은 시연 중 실제 적립으로만 채워짐.
 */
@Component
public class SampleHistorySeeder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CustomerRepository customerRepo;
    private final VisitLogRepository visitRepo;
    private final PhoneHasher hasher;

    public SampleHistorySeeder(CustomerRepository customerRepo, VisitLogRepository visitRepo, PhoneHasher hasher) {
        this.customerRepo = customerRepo;
        this.visitRepo = visitRepo;
        this.hasher = hasher;
    }

    /** 호출하는 쪽 트랜잭션 안에서 실행할 것 */
    public int seed(Store store) {
        Random rnd = new Random(42);
        LocalDate today = LocalDate.now(KST);
        int visits = 0;
        for (int i = 1; i <= 40; i++) {
            String phone = String.format("0109000%04d", i);
            Customer c = customerRepo.save(new Customer(store, hasher.hash(store.getId(), phone), hasher.last4(phone)));
            int daysAgo = (int) (55 * Math.pow(rnd.nextDouble(), 0.8)) + 1;
            LocalDate day = today.minusDays(daysAgo);
            int cycle = 5 + rnd.nextInt(16);
            boolean churned = rnd.nextDouble() < 0.25;
            LocalDate lastDay = churned ? day.plusDays((long) cycle * (1 + rnd.nextInt(3))) : today;
            boolean first = true;
            while (day.isBefore(today) && !day.isAfter(lastDay)) {
                Instant at = day.atTime(11 + rnd.nextInt(9), rnd.nextInt(60)).atZone(KST).toInstant();
                visitRepo.save(new VisitLog(store, c, at, VisitSource.QR, first, null));
                if (first) { c.setFirstVisitAt(at); first = false; }
                visits++;
                day = day.plusDays(Math.max(1, cycle + rnd.nextInt(7) - 3));
            }
        }
        return visits;
    }
}

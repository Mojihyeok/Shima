package kr.ac.ajou.shima.config;

import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.repository.StoreRepository;
import kr.ac.ajou.shima.service.StatsService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;

/** 매주 월요일 00:05(KST) — 지난주 지표를 metric_snapshot에 저장 */
@Configuration
@EnableScheduling
public class WeeklySnapshotJob {

    private final StoreRepository storeRepo;
    private final StatsService statsService;

    public WeeklySnapshotJob(StoreRepository storeRepo, StatsService statsService) {
        this.storeRepo = storeRepo;
        this.statsService = statsService;
    }

    @Scheduled(cron = "0 5 0 * * MON", zone = "Asia/Seoul")
    public void snapshotLastWeek() {
        LocalDate lastWeek = StatsService.thisWeekStart().minusWeeks(1);
        for (Store store : storeRepo.findAll()) {
            statsService.metrics(store, lastWeek); // 없으면 계산 후 저장, 있으면 그대로
        }
    }
}

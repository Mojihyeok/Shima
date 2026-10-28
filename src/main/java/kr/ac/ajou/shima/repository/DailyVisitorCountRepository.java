package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.DailyVisitorCount;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyVisitorCountRepository extends JpaRepository<DailyVisitorCount, Long> {
    Optional<DailyVisitorCount> findByStoreAndDay(Store store, LocalDate day);
    List<DailyVisitorCount> findByStoreAndDayBetween(Store store, LocalDate from, LocalDate to);
}

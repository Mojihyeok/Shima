package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.MetricSnapshot;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {
    Optional<MetricSnapshot> findByStoreAndWeekStart(Store store, LocalDate weekStart);
}

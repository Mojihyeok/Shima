package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {
    long countByStoreAndTypeAndOccurredAtBetween(Store store, UsageEvent.Type type, Instant from, Instant to);
}

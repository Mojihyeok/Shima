package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.MarketingAction;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MarketingActionRepository extends JpaRepository<MarketingAction, Long> {
    List<MarketingAction> findByStoreAndActedAtBetweenOrderByActedAtAsc(Store store, Instant from, Instant to);
    Optional<MarketingAction> findByIdAndStore(Long id, Store store);
}

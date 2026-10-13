package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.GrantType;
import kr.ac.ajou.shima.domain.RewardGrant;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RewardGrantRepository extends JpaRepository<RewardGrant, Long> {
    long countByStoreAndTypeAndGrantedAtBetween(Store store, GrantType type, Instant from, Instant to);
}

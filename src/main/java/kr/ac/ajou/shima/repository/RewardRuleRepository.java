package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.RewardRule;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RewardRuleRepository extends JpaRepository<RewardRule, Long> {
    Optional<RewardRule> findFirstByStoreAndActiveTrue(Store store);
}

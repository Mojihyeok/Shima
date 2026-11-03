package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.AiMentionCheck;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiMentionCheckRepository extends JpaRepository<AiMentionCheck, Long> {
    Optional<AiMentionCheck> findFirstByStoreOrderByCheckedAtDesc(Store store);
}

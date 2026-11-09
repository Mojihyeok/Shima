package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.FeedbackLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackLogRepository extends JpaRepository<FeedbackLog, Long> {
}

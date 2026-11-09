package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.CoachTask;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoachTaskRepository extends JpaRepository<CoachTask, Long> {
    Optional<CoachTask> findFirstByStoreOrderByCreatedAtDesc(Store store);
    List<CoachTask> findTop10ByStoreOrderByCreatedAtDesc(Store store);
    Optional<CoachTask> findByIdAndStore(Long id, Store store);
}

package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.DiagnosisResult;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiagnosisResultRepository extends JpaRepository<DiagnosisResult, Long> {
    Optional<DiagnosisResult> findFirstByStoreOrderByCreatedAtDesc(Store store);
}

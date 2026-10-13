package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {
    /** 사장님의 매장 (P0: 사장님 1명 = 매장 1개) */
    Optional<Store> findFirstByOwner_Id(Long ownerId);
}

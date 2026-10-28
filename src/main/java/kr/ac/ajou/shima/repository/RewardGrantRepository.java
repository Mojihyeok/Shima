package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.domain.GrantType;
import kr.ac.ajou.shima.domain.RewardGrant;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RewardGrantRepository extends JpaRepository<RewardGrant, Long> {
    long countByStoreAndTypeAndGrantedAtBetween(Store store, GrantType type, Instant from, Instant to);

    /** 손님 데이터 삭제용 */
    @Modifying
    @Query("delete from RewardGrant g where g.customer = :customer")
    int deleteByCustomer(@Param("customer") Customer customer);
}

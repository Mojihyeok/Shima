package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.VisitLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VisitLogRepository extends JpaRepository<VisitLog, Long> {

    /** 해당 손님의 누적 방문(=도장) 수 */
    long countByCustomer(Customer customer);

    /** 오늘 이미 적립했는지 — 중복 적립 방지 */
    boolean existsByCustomerAndVisitedAtGreaterThanEqual(Customer customer, Instant startOfToday);

    /** 손님의 마지막 방문 */
    Optional<VisitLog> findTopByCustomerOrderByVisitedAtDesc(Customer customer);

    /** 매장의 특정 시점 이후 방문 (오늘 방문 손님, 최신순) */
    List<VisitLog> findByStoreAndVisitedAtGreaterThanEqualOrderByVisitedAtDesc(Store store, Instant since);

    /** 매장의 특정 시점 이전 전체 방문 (평균 방문 주기 계산용) */
    List<VisitLog> findByStoreAndVisitedAtBefore(Store store, Instant until);

    /** 매장의 기간 내 방문 (주간 집계용) */
    List<VisitLog> findByStoreAndVisitedAtBetween(Store store, Instant from, Instant to);

    /** 손님 데이터 삭제용 */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from VisitLog v where v.customer = :customer")
    int deleteByCustomer(@Param("customer") Customer customer);

    /** 손님별 누적 방문 수·마지막 방문 — 매장 전체를 쿼리 1번으로 (손님마다 2번씩 조회하던 N+1 제거) */
    interface CustomerVisitStat {
        Long getCustomerId();
        Long getVisitCount();
        Instant getLastVisitAt();
    }

    @Query("select v.customer.id as customerId, count(v) as visitCount, max(v.visitedAt) as lastVisitAt " +
           "from VisitLog v where v.store = :store group by v.customer.id")
    List<CustomerVisitStat> visitStatsByStore(@Param("store") Store store);
}

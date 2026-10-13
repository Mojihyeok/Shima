package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.VisitLog;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

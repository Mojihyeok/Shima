package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByStoreAndPhoneHash(Store store, String phoneHash);
    List<Customer> findByStore(Store store);

    /** 기간 중 첫 방문한 손님의 유입 경로별 수 — [유입경로(null 가능), 수] */
    @Query("select c.acquisitionSource, count(c) from Customer c " +
           "where c.store = :store and c.firstVisitAt >= :from and c.firstVisitAt < :to group by c.acquisitionSource")
    List<Object[]> countSourcesByFirstVisit(@Param("store") Store store, @Param("from") Instant from, @Param("to") Instant to);
}

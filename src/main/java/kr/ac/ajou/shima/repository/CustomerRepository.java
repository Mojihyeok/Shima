package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByStoreAndPhoneHash(Store store, String phoneHash);
    List<Customer> findByStore(Store store);
}

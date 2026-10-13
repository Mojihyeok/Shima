package kr.ac.ajou.shima.repository;

import kr.ac.ajou.shima.domain.OwnerUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OwnerUserRepository extends JpaRepository<OwnerUser, Long> {
    Optional<OwnerUser> findByLoginId(String loginId);
}

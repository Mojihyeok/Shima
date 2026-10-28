package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.repository.CustomerRepository;
import kr.ac.ajou.shima.repository.RewardGrantRepository;
import kr.ac.ajou.shima.repository.VisitLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 손님 개인정보 삭제 — 손님 행 + 방문 기록 + 지급 이력을 완전히 지운다(복구 불가).
 * 이미 확정된 주간 스냅샷(숫자 집계)은 개인을 알아볼 수 없으므로 남는다.
 */
@Service
public class CustomerDataService {

    private static final Logger log = LoggerFactory.getLogger(CustomerDataService.class);

    private final CustomerRepository customerRepository;
    private final VisitLogRepository visitLogRepository;
    private final RewardGrantRepository rewardGrantRepository;

    public CustomerDataService(CustomerRepository customerRepository, VisitLogRepository visitLogRepository,
                               RewardGrantRepository rewardGrantRepository) {
        this.customerRepository = customerRepository;
        this.visitLogRepository = visitLogRepository;
        this.rewardGrantRepository = rewardGrantRepository;
    }

    @Transactional
    public void delete(Customer customer, String by) {
        int grants = rewardGrantRepository.deleteByCustomer(customer);
        int visits = visitLogRepository.deleteByCustomer(customer);
        customerRepository.delete(customer);
        // 전화번호·이름은 로그에 남기지 않음
        log.info(">> [privacy] 손님 데이터 삭제 store={} customerId={} visits={} grants={} by={}",
                customer.getStore().getId(), customer.getId(), visits, grants, by);
    }
}

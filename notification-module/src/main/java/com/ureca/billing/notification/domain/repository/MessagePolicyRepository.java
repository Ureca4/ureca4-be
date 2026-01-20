package com.ureca.billing.notification.domain.repository;

import com.ureca.billing.notification.domain.entity.MessagePolicy;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MessagePolicyRepository extends CrudRepository<MessagePolicy, Long> {
    
    Optional<MessagePolicy> findByPolicyType(String policyType);
}
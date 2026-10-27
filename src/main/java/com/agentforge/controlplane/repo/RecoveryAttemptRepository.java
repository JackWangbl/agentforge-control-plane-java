package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.RecoveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryAttemptRepository extends JpaRepository<RecoveryAttempt, Long> {
    List<RecoveryAttempt> findByFailureCaseIdAndTenantIdOrderByCreatedAtAsc(Long failureCaseId, Long tenantId);
    Optional<RecoveryAttempt> findByAttemptKeyAndTenantId(String attemptKey, Long tenantId);
}

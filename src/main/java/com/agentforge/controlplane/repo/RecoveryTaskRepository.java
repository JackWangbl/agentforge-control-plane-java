package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.RecoveryTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecoveryTaskRepository extends JpaRepository<RecoveryTask, Long> {
    Optional<RecoveryTask> findByTaskKeyAndTenantId(String taskKey, Long tenantId);
    Optional<RecoveryTask> findByAttemptIdAndTenantId(Long attemptId, Long tenantId);
    long countByTenantIdAndStatus(Long tenantId, RecoveryTask.Status status);
    long countByTenantIdAndAgentIdAndStatus(Long tenantId, Long agentId, RecoveryTask.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecoveryTask> findFirstByStatusAndAvailableAtLessThanEqualOrderByQueueSequenceAsc(RecoveryTask.Status status, Instant now);

    List<RecoveryTask> findByStatusAndLeaseUntilBefore(RecoveryTask.Status status, Instant before);
    List<RecoveryTask> findTop100ByStatusOrderByQueueSequenceAsc(RecoveryTask.Status status);
}

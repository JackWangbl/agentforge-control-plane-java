package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ExecutionCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExecutionCheckpointRepository extends JpaRepository<ExecutionCheckpoint, Long> {
    List<ExecutionCheckpoint> findByExecutionRecordIdAndTenantIdOrderBySequenceNoAsc(Long executionRecordId, Long tenantId);
    Optional<ExecutionCheckpoint> findFirstByExecutionRecordIdAndTenantIdAndSafeToResumeTrueOrderBySequenceNoDesc(Long executionRecordId, Long tenantId);
    Optional<ExecutionCheckpoint> findFirstByFailureCaseIdAndTenantIdAndSafeToResumeTrueOrderBySequenceNoDesc(Long failureCaseId, Long tenantId);
    Optional<ExecutionCheckpoint> findFirstByExecutionRecordIdAndTenantIdOrderBySequenceNoDesc(Long executionRecordId, Long tenantId);
}

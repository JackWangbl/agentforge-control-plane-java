package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ExecutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.Collection;

public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecord, Long>, JpaSpecificationExecutor<ExecutionRecord> {
    Optional<ExecutionRecord> findByExecutionKeyAndTenantId(String executionKey, Long tenantId);
    Optional<ExecutionRecord> findByIdAndTenantId(Long id, Long tenantId);
    Optional<ExecutionRecord> findFirstByTenantIdAndAgentIdAndSessionIdAndStatusInOrderByFinishedAtDesc(
            Long tenantId, Long agentId, String sessionId, Collection<ExecutionRecord.Status> statuses);

    @Query("select count(e) from ExecutionRecord e where e.tenantId=:tenant and e.executionKind=com.agentforge.controlplane.domain.ExecutionRecord.Kind.ORIGINAL and e.status in (com.agentforge.controlplane.domain.ExecutionRecord.Status.SUCCEEDED,com.agentforge.controlplane.domain.ExecutionRecord.Status.FAILED,com.agentforge.controlplane.domain.ExecutionRecord.Status.TIMED_OUT,com.agentforge.controlplane.domain.ExecutionRecord.Status.MANUAL_REQUIRED) and e.finishedAt between :from and :to")
    long countCompletedOriginals(@Param("tenant") Long tenantId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(e) from ExecutionRecord e where e.tenantId=:tenant and e.executionKind=com.agentforge.controlplane.domain.ExecutionRecord.Kind.ORIGINAL and e.status in (com.agentforge.controlplane.domain.ExecutionRecord.Status.FAILED,com.agentforge.controlplane.domain.ExecutionRecord.Status.TIMED_OUT,com.agentforge.controlplane.domain.ExecutionRecord.Status.MANUAL_REQUIRED) and e.finishedAt between :from and :to")
    long countFailedOriginals(@Param("tenant") Long tenantId, @Param("from") Instant from, @Param("to") Instant to);
}

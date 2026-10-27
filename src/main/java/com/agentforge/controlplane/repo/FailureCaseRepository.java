package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.FailureCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FailureCaseRepository extends JpaRepository<FailureCase, Long>, JpaSpecificationExecutor<FailureCase> {
    Optional<FailureCase> findByCaseKeyAndTenantId(String caseKey, Long tenantId);
    Optional<FailureCase> findByExecutionRecordIdAndTenantId(Long executionRecordId, Long tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FailureCase f set f.status=com.agentforge.controlplane.domain.FailureCase.Status.RECOVERING where f.id=:id and f.tenantId=:tenant and f.status=:expected")
    int claim(@Param("id") Long id, @Param("tenant") Long tenantId, @Param("expected") FailureCase.Status expected);
}

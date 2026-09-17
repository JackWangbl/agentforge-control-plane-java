package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository
        extends JpaRepository<Conversation, Long>, JpaSpecificationExecutor<Conversation> {
    Optional<Conversation> findBySessionId(String sessionId);

    List<Conversation> findTop5ByTenantIdOrderByUpdatedAtDescIdDesc(Long tenantId);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, String status);

    @Query("select avg(c.latencyMs) from Conversation c where c.tenantId = :tenantId or c.tenantId is null")
    Double avgLatencyByTenant(Long tenantId);

    @Query("select coalesce(sum(c.totalTokens), 0) from Conversation c where c.tenantId = :tenantId or c.tenantId is null")
    Long sumTokensByTenant(Long tenantId);
}

package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ConversationContextSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationContextSummaryRepository extends JpaRepository<ConversationContextSummary, Long> {
    Optional<ConversationContextSummary> findByTenantIdAndSessionIdAndAgentId(Long tenantId, String sessionId, Long agentId);

    Optional<ConversationContextSummary> findFirstByTenantIdAndSessionIdOrderByUpdatedAtDesc(Long tenantId, String sessionId);
}

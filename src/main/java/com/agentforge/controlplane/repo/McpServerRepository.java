package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface McpServerRepository extends JpaRepository<McpServer, Long>, JpaSpecificationExecutor<McpServer> {
    Optional<McpServer> findByName(String name);

    List<McpServer> findByTenantIdAndEnabledTrue(Long tenantId);

    List<McpServer> findByTransport(String transport);
}

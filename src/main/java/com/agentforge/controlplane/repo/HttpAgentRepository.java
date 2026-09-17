package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.HttpAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface HttpAgentRepository extends JpaRepository<HttpAgent, Long>, JpaSpecificationExecutor<HttpAgent> {
    Optional<HttpAgent> findByName(String name);

    List<HttpAgent> findByTenantIdAndEnabledTrue(Long tenantId);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}

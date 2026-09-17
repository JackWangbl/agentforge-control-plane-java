package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long>, JpaSpecificationExecutor<Agent> {
    Optional<Agent> findByName(String name);

    List<Agent> findByTenantIdOrderByIdAsc(Long tenantId);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}

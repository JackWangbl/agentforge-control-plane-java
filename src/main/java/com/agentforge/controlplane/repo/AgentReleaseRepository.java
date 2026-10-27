package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.AgentRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentReleaseRepository extends JpaRepository<AgentRelease, Long> {
    Optional<AgentRelease> findByIdAndAgentId(Long id, Long agentId);

    Optional<AgentRelease> findFirstByAgentIdOrderByRevisionDesc(Long agentId);

    void deleteByAgentId(Long agentId);
}

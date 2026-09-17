package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.SandboxPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface SandboxPolicyRepository
        extends JpaRepository<SandboxPolicy, Long>, JpaSpecificationExecutor<SandboxPolicy> {
    Optional<SandboxPolicy> findByName(String name);
}

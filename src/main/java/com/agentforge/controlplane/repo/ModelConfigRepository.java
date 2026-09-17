package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long>, JpaSpecificationExecutor<ModelConfig> {
    Optional<ModelConfig> findByName(String name);

    List<ModelConfig> findByTenantIdAndEnabledTrue(Long tenantId);
}

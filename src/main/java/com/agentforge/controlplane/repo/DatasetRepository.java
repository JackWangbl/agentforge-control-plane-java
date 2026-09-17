package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.Dataset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface DatasetRepository extends JpaRepository<Dataset, Long>, JpaSpecificationExecutor<Dataset> {
    List<Dataset> findByTenantIdAndKind(Long tenantId, String kind);
}

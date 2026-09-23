package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.RerankStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RerankStoreRepository extends JpaRepository<RerankStore, Long> {
    List<RerankStore> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<RerankStore> findByTenantIdAndName(Long tenantId, String name);
}

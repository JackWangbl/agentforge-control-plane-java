package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.VectorStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VectorStoreRepository extends JpaRepository<VectorStore, Long> {
    List<VectorStore> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<VectorStore> findByTenantIdAndName(Long tenantId, String name);

    List<VectorStore> findByIdIn(List<Long> ids);
}

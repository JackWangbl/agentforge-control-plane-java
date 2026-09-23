package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {
    List<KnowledgeBase> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<KnowledgeBase> findByTenantIdAndName(Long tenantId, String name);

    List<KnowledgeBase> findByVectorStoreId(Long vectorStoreId);

    long countByEmbeddingModelId(Long embeddingModelId);
}

package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    List<KnowledgeDocument> findByKnowledgeIdOrderByIdDesc(Long knowledgeId);

    long countByKnowledgeId(Long knowledgeId);

    long countByKnowledgeIdAndStatus(Long knowledgeId, String status);

    List<KnowledgeDocument> findByKnowledgeIdAndStatus(Long knowledgeId, String status);
}

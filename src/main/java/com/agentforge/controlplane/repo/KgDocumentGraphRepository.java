package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.KgDocumentGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KgDocumentGraphRepository extends JpaRepository<KgDocumentGraph, Long> {
    Optional<KgDocumentGraph> findByDocumentId(Long documentId);

    List<KgDocumentGraph> findByKnowledgeId(Long knowledgeId);

    void deleteByKnowledgeId(Long knowledgeId);

    void deleteByDocumentId(Long documentId);
}

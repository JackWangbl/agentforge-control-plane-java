package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.KnowledgeMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeMemberRepository extends JpaRepository<KnowledgeMember, Long> {
    List<KnowledgeMember> findByKnowledgeIdOrderByIdAsc(Long knowledgeId);

    List<KnowledgeMember> findByUserId(Long userId);

    Optional<KnowledgeMember> findByKnowledgeIdAndUserId(Long knowledgeId, Long userId);

    long countByKnowledgeIdAndRole(Long knowledgeId, String role);

    void deleteByKnowledgeId(Long knowledgeId);
}

package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.MemoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryItemRepository extends JpaRepository<MemoryItem, Long> {

    List<MemoryItem> findByTenantIdAndSubjectKeyAndDeletedAtIsNullOrderByPinnedDescUpdatedAtDescIdDesc(
            Long tenantId, String subjectKey);

    long countByTenantIdAndSubjectKeyAndDeletedAtIsNull(Long tenantId, String subjectKey);

    Optional<MemoryItem> findByIdAndTenantIdAndSubjectKeyAndDeletedAtIsNull(
            Long id, Long tenantId, String subjectKey);
}

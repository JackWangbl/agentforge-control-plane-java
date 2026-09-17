package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.DatasetCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetCaseRepository extends JpaRepository<DatasetCase, Long> {
    List<DatasetCase> findByDatasetIdOrderByIdAsc(Long datasetId);

    List<DatasetCase> findByDatasetIdAndEnabledTrueOrderByIdAsc(Long datasetId);

    long countByDatasetId(Long datasetId);

    void deleteByDatasetId(Long datasetId);
}

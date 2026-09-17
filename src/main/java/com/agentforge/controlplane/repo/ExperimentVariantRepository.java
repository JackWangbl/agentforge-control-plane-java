package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ExperimentVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperimentVariantRepository extends JpaRepository<ExperimentVariant, Long> {
    List<ExperimentVariant> findByExperimentIdOrderByIdAsc(Long experimentId);

    void deleteByExperimentId(Long experimentId);
}

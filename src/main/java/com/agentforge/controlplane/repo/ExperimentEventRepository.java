package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ExperimentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperimentEventRepository extends JpaRepository<ExperimentEvent, Long> {
    List<ExperimentEvent> findByExperimentId(Long experimentId);

    List<ExperimentEvent> findByExperimentIdAndKind(Long experimentId, String kind);

    void deleteByExperimentId(Long experimentId);
}

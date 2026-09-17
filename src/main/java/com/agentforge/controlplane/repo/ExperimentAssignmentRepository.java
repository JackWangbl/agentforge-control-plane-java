package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.ExperimentAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignment, Long> {
    Optional<ExperimentAssignment> findByExperimentIdAndUnitKey(Long experimentId, String unitKey);

    List<ExperimentAssignment> findByExperimentId(Long experimentId);

    void deleteByExperimentId(Long experimentId);
}

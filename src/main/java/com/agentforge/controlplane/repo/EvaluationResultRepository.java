package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.EvaluationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvaluationResultRepository extends JpaRepository<EvaluationResult, Long> {
    List<EvaluationResult> findByRunIdOrderByIdAsc(Long runId);

    List<EvaluationResult> findByRunIdAndStatusOrderByIdAsc(Long runId, String status);

    void deleteByRunId(Long runId);

    void deleteByRunIdAndStatusIn(Long runId, List<String> statuses);
}

package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.EvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EvaluationRunRepository
        extends JpaRepository<EvaluationRun, Long>, JpaSpecificationExecutor<EvaluationRun> {

    /** 离线 worker 每轮认领最老的一条排队任务。 */
    Optional<EvaluationRun> findFirstByStatusAndModeInOrderByIdAsc(String status, Collection<String> modes);

    List<EvaluationRun> findByTenantIdOrderByIdDesc(Long tenantId);
}

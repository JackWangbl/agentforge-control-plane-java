package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.Trace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface TraceRepository extends JpaRepository<Trace, Long>, JpaSpecificationExecutor<Trace> {
    Optional<Trace> findByTraceId(String traceId);

    List<Trace> findBySessionIdOrderByStartedAtDesc(String sessionId);

    List<Trace> findByTraceIdIn(List<String> traceIds);
}

package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.OpenCliEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OpenCliEndpointRepository
        extends JpaRepository<OpenCliEndpoint, Long>, JpaSpecificationExecutor<OpenCliEndpoint> {
}

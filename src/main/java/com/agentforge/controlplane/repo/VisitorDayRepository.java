package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.VisitorDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface VisitorDayRepository extends JpaRepository<VisitorDay, Long> {

    long countByVisitedOn(LocalDate day);
}

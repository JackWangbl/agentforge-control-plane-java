package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    List<User> findByTenantIdOrderByIdAsc(Long tenantId);

    int countByRoleId(Long roleId);
}

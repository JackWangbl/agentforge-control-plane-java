package com.agentforge.controlplane.repo;

import com.agentforge.controlplane.domain.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByToken(String token);

    Optional<AuthToken> findByTrialKey(String trialKey);

    void deleteByToken(String token);
}

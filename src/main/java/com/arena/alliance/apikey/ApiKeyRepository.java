package com.arena.alliance.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    List<ApiKey> findByUserIdOrderByIdAsc(Long userId);

    Optional<ApiKey> findByTokenHash(String tokenHash);

    List<ApiKey> findByStatus(ApiKey.Status status);

    Optional<ApiKey> findFirstByGameUsernameAndStatusAndIdNot(String gameUsername, ApiKey.Status status, Long id);
}

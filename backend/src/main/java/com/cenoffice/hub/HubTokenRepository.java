package com.cenoffice.hub;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubTokenRepository extends JpaRepository<HubToken, Long> {
    Optional<HubToken> findByTokenHash(String tokenHash);
    List<HubToken> findByOwnerOrderByCreatedAtDesc(String owner);
    long countByOwnerAndRevokedFalse(String owner);
}

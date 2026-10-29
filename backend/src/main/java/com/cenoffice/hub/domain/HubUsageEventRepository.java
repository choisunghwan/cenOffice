package com.cenoffice.hub.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HubUsageEventRepository extends JpaRepository<HubUsageEvent, Long> {

}

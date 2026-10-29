package com.cenoffice.office.agent;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAgentRepository extends JpaRepository<AiAgent, Long> {
    java.util.Optional<AiAgent> findByCode(String code);
}

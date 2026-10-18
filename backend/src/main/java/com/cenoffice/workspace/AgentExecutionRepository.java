package com.cenoffice.workspace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {
    List<AgentExecution> findByAiTaskIdOrderByStartedAtAsc(Long aiTaskId);
    void deleteByAiTaskId(Long aiTaskId);
}

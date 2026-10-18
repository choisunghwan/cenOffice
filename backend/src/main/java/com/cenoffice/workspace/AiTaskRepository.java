package com.cenoffice.workspace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTaskRepository extends JpaRepository<AiTask, Long> {
    List<AiTask> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<AiTask> findTop50ByOrderByCreatedAtDesc();
    List<AiTask> findByProjectId(Long projectId);
}

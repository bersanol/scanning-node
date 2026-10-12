package org.example.scanning.manager.repository;

import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.manager.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, String> {

    Optional<Task> findFirstByNodeIdAndStatusOrderByCreatedAtAsc(String nodeId, TaskStatus status);

    List<Task> findByNodeIdAndStatus(String nodeId, TaskStatus status);

    List<Task> findByNodeIdOrderByCreatedAtDesc(String nodeId);

}

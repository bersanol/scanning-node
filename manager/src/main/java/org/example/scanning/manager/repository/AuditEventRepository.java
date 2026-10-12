package org.example.scanning.manager.repository;

import org.example.scanning.manager.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    boolean existsByEventId(String eventId);

    List<AuditEvent> findByNodeIdOrderByOccurredAtDesc(String nodeId);

    List<AuditEvent> findAllByOrderByOccurredAtDesc();
}

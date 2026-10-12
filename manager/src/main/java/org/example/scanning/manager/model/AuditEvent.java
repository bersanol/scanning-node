package org.example.scanning.manager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Install/upgrade event reported by a node. The unique {@code eventId} deduplicates offline replays. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String nodeId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(length = 4000)
    private String detail;

    @Column(nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(String eventId, String nodeId, String type, String detail, Instant occurredAt) {
        this.eventId = eventId;
        this.nodeId = nodeId;
        this.type = type;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}

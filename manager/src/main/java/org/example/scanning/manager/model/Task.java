package org.example.scanning.manager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.common.Protocol.TaskType;

import java.time.Instant;
import java.util.UUID;

/**
 * A scan or upgrade for one node, including its result once finished. A task is the unit of
 * idempotency: a result for a task that is already terminal is a duplicate and is ignored.
 */
@Entity
@Table(name = "tasks", indexes = @Index(name = "ix_tasks_node_status", columnList = "node_id, status"))
public class Task {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    /** SCAN: IPv4 address or CIDR. */
    private String target;

    /** UPGRADE: "app" or "nmap", version to reach, where to fetch it and its checksum. */
    private String component;
    private String targetVersion;
    @Column(length = 1024)
    private String artifactUrl;
    private String sha256;

    private Instant createdAt;
    private Instant assignedAt;
    private Instant finishedAt;
    private int retryCount;

    // Result, filled in when the node reports DONE or FAILED.
    private Integer exitCode;
    @Column(length = 2000)
    private String error;
    @Lob
    private String rawOutput;
    /** JSON array of {@link org.example.scanning.common.Protocol.Host}. */
    @Lob
    private String structuredJson;

    protected Task() {
    }

    public static Task scan(String nodeId, String target) {
        Task t = newTask(nodeId, TaskType.SCAN);
        t.target = target;
        return t;
    }

    public static Task upgrade(String nodeId, String component, String targetVersion, String artifactUrl, String sha256) {
        Task t = newTask(nodeId, TaskType.UPGRADE);
        t.component = component;
        t.targetVersion = targetVersion;
        t.artifactUrl = artifactUrl;
        t.sha256 = sha256;
        return t;
    }

    private static Task newTask(String nodeId, TaskType type) {
        Task t = new Task();
        t.id = UUID.randomUUID().toString();
        t.nodeId = nodeId;
        t.type = type;
        t.status = TaskStatus.QUEUED;
        t.createdAt = Instant.now();
        return t;
    }

    public String getId() { return id; }
    public String getNodeId() { return nodeId; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getTarget() { return target; }
    public String getComponent() { return component; }
    public String getTargetVersion() { return targetVersion; }
    public String getArtifactUrl() { return artifactUrl; }
    public String getSha256() { return sha256; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
    public String getStructuredJson() { return structuredJson; }
    public void setStructuredJson(String structuredJson) { this.structuredJson = structuredJson; }
}

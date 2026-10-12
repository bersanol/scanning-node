package org.example.scanning.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * The whole node &lt;-&gt; manager wire protocol. Every call is node-initiated:
 *
 * <pre>
 *   POST /api/v1/enroll                      EnrollRequest  -> EnrollResponse   (enrollment token)
 *   POST /api/v1/nodes/{id}/heartbeat        Heartbeat      -> HeartbeatResponse (long-poll, ≤30 s)
 *   POST /api/v1/tasks/{taskId}/result       TaskResult     -> Ack              (idempotent)
 *   POST /api/v1/nodes/{id}/events           List&lt;Event&gt;    -> Ack              (idempotent)
 * </pre>
 *
 * Authenticated calls send {@code Authorization: Bearer <nodeId>:<secret>}.
 */
public final class Protocol {

    private Protocol() {
    }

    public enum TaskType { SCAN, UPGRADE }

    /** QUEUED -> ASSIGNED -> RUNNING -> DONE | FAILED. INTERRUPTED is re-queued once, then FAILED. */
    public enum TaskStatus {
        QUEUED, ASSIGNED, RUNNING, DONE, FAILED, INTERRUPTED;

        public boolean isTerminal() {
            return this == DONE || this == FAILED;
        }
    }

    public record EnrollRequest(@NotBlank String enrollmentToken, @NotBlank String nodeId,
                                String hostname, String os, String appVersion, String nmapVersion) {
    }

    public record EnrollResponse(String nodeId, String secret) {
    }

    /** {@code heldTaskIds}: tasks the node has durably accepted, so the manager can confirm delivery. */
    public record Heartbeat(String hostname, String os, String appVersion, String nmapVersion, List<String> heldTaskIds) {
        public List<String> held() {
            return heldTaskIds == null ? List.of() : heldTaskIds;
        }
    }

    /** {@code task} is null when nothing is queued. */
    public record HeartbeatResponse(Task task) {
    }

    /** SCAN uses {@code target}; UPGRADE uses component ("app"|"nmap"), targetVersion, artifactUrl, sha256. */
    public record Task(String taskId, TaskType type, String target,
                       String component, String targetVersion, String artifactUrl, String sha256) {
    }

    public record Port(int port, String protocol, String state, String service) {
    }

    public record Host(String address, String state, List<Port> ports) {
    }

    /** Status must be DONE, FAILED or INTERRUPTED. */
    public record TaskResult(@NotNull TaskStatus status, Integer exitCode, String rawOutput, String error, List<Host> hosts) {
    }

    /** Install/upgrade event; {@code eventId} is a node-generated UUID so offline replays deduplicate. */
    public record Event(String eventId, String type, String detail, Instant occurredAt) {
    }

    public record Ack(int accepted, int duplicates) {
    }
}

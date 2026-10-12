package org.example.scanning.manager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.scanning.common.Protocol;
import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.manager.model.Task;
import org.example.scanning.manager.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Task queue per node: creation, long-poll delivery, acknowledgement and idempotent results. */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    /** An INTERRUPTED task is re-queued this many times before it is FAILED. */
    static final int MAX_RETRIES = 1;
    /** An ASSIGNED task the node never acknowledged is re-queued after this (lost response / crash). */
    static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(45);
    private static final Pattern IPV4_OR_CIDR = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)(/([0-9]|[12]\\d|3[0-2]))?$");

    private final TaskRepository tasks;
    private final ObjectMapper json;
    private final Duration longPoll;
    /** Heartbeats currently held open, by node ID. */
    private final Map<String, DeferredResult<HeartbeatResponse>> waiting = new ConcurrentHashMap<>();

    public TaskService(TaskRepository tasks, ObjectMapper json, @Value("${scanning.long-poll:30s}") Duration longPoll) {
        this.tasks = tasks;
        this.json = json;
        this.longPoll = longPoll;
    }

    @Transactional
    public Task createScan(String nodeId, String target) {
        if (target == null || !IPV4_OR_CIDR.matcher(target.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target must be an IPv4 address or CIDR");
        }
        Task task = tasks.save(Task.scan(nodeId, target.trim()));
        log.info("Queued scan {} of {} on node {}", task.getId(), target, nodeId);
        wake(nodeId);
        return task;
    }

    @Transactional
    public Task createUpgrade(String nodeId, String component, String version, String artifactUrl, String sha256) {
        if (!"app".equals(component) && !"nmap".equals(component)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "component must be 'app' or 'nmap'");
        }
        Task task = tasks.save(Task.upgrade(nodeId, component, version, artifactUrl, sha256));
        log.info("Queued {} upgrade to {} on node {}", component, version, nodeId);
        wake(nodeId);
        return task;
    }

    /**
     * Long-poll: confirms the tasks the node holds, then returns the next queued task right away
     * or parks the request until one is created or {@code scanning.long-poll} elapses.
     */
    @Transactional
    public DeferredResult<HeartbeatResponse> poll(String nodeId, Collection<String> heldTaskIds) {
        acknowledge(nodeId, heldTaskIds);
        DeferredResult<HeartbeatResponse> result = new DeferredResult<>(longPoll.toMillis(), new HeartbeatResponse(null));
        Optional<Protocol.Task> next = assignNext(nodeId);
        if (next.isPresent()) {
            result.setResult(new HeartbeatResponse(next.get()));
        } else {
            waiting.put(nodeId, result);
            result.onCompletion(() -> waiting.remove(nodeId, result));
        }
        return result;
    }

    private void wake(String nodeId) {
        DeferredResult<HeartbeatResponse> parked = waiting.remove(nodeId);
        if (parked != null) {
            parked.setResult(new HeartbeatResponse(assignNext(nodeId).orElse(null)));
        }
    }

    private Optional<Protocol.Task> assignNext(String nodeId) {
        return tasks.findFirstByNodeIdAndStatusOrderByCreatedAtAsc(nodeId, TaskStatus.QUEUED).map(task -> {
            task.setStatus(TaskStatus.ASSIGNED);
            task.setAssignedAt(Instant.now());
            return toProtocol(tasks.save(task));
        });
    }

    /** Held tasks become RUNNING; ASSIGNED tasks the node does not hold are re-queued after the timeout. */
    void acknowledge(String nodeId, Collection<String> heldTaskIds) {
        for (Task task : tasks.findByNodeIdAndStatus(nodeId, TaskStatus.ASSIGNED)) {
            if (heldTaskIds.contains(task.getId())) {
                task.setStatus(TaskStatus.RUNNING);
                tasks.save(task);
            } else if (task.getAssignedAt() != null && task.getAssignedAt().plus(ASSIGNMENT_TIMEOUT).isBefore(Instant.now())) {
                log.warn("Task {} assigned to {} was never acknowledged; re-queuing", task.getId(), nodeId);
                requeue(task, "assignment lost");
            }
        }
    }

    /**
     * Records the outcome. Idempotent: a result for a task that is already terminal is a duplicate
     * and changes nothing. INTERRUPTED re-queues the task up to {@link #MAX_RETRIES} times, then FAILED.
     */
    @Transactional
    public Ack recordResult(String nodeId, String taskId, TaskResult result) {
        Task task = get(taskId);
        if (!task.getNodeId().equals(nodeId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "task " + taskId + " belongs to another node");
        }
        if (task.getStatus().isTerminal() || task.getStatus() == TaskStatus.QUEUED) {
            return new Ack(0, 1);
        }
        switch (result.status()) {
            case DONE, FAILED -> {
                task.setStatus(result.status());
                task.setExitCode(result.exitCode());
                task.setRawOutput(result.rawOutput());
                task.setError(result.error());
                task.setStructuredJson(toJson(result.hosts()));
                task.setFinishedAt(Instant.now());
                log.info("Task {} on node {} finished {}", taskId, nodeId, result.status());
            }
            case INTERRUPTED -> {
                if (task.getRetryCount() < MAX_RETRIES) {
                    requeue(task, "interrupted: " + result.error());
                    wake(nodeId);
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setError("interrupted again after retry: " + result.error());
                    task.setFinishedAt(Instant.now());
                    log.warn("Task {} interrupted again on node {}; marked FAILED", taskId, nodeId);
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "result status must be DONE, FAILED or INTERRUPTED");
        }
        tasks.save(task);
        return new Ack(1, 0);
    }

    private void requeue(Task task, String reason) {
        task.setRetryCount(task.getRetryCount() + 1);
        task.setStatus(TaskStatus.QUEUED);
        task.setAssignedAt(null);
        task.setError(reason + " (retry " + task.getRetryCount() + ")");
        tasks.save(task);
        log.warn("Task {} re-queued: {}", task.getId(), reason);
    }

    public Task get(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task " + taskId));
    }

    public List<Task> all() {
        return tasks.findAllByOrderByCreatedAtDesc();
    }

    public List<Task> forNode(String nodeId) {
        return tasks.findByNodeIdOrderByCreatedAtDesc(nodeId);
    }

    public List<Protocol.Host> hosts(Task task) {
        try {
            return task.getStructuredJson() == null ? List.of()
                    : List.of(json.readValue(task.getStructuredJson(), Protocol.Host[].class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    static Protocol.Task toProtocol(Task t) {
        return new Protocol.Task(t.getId(), t.getType(), t.getTarget(), t.getComponent(),
                t.getTargetVersion(), t.getArtifactUrl(), t.getSha256());
    }

    private String toJson(List<Protocol.Host> hosts) {
        try {
            return json.writeValueAsString(hosts == null ? List.of() : hosts);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unserialisable hosts", e);
        }
    }
}

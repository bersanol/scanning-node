package org.example.scanning.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.Host;
import org.example.scanning.common.Protocol.Port;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.manager.model.Task;
import org.example.scanning.manager.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository tasks;

    private TaskService service;

    @BeforeEach
    void setUp() {
        lenient().when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new TaskService(tasks, new ObjectMapper(), Duration.ofSeconds(30));
    }

    @Test
    void createScanQueuesValidTargetOnly() {
        Task task = service.createScan("node-1", "10.0.0.0/24");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.getTarget()).isEqualTo("10.0.0.0/24");

        for (String bad : List.of("example.com", "256.1.1.1", "10.0.0.0/33", "127.0.0.1; rm -rf /", "")) {
            assertThatThrownBy(() -> service.createScan("node-1", bad))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void pollReturnsQueuedTaskImmediatelyAsAssigned() {
        Task queued = Task.scan("node-1", "127.0.0.1");
        when(tasks.findFirstByNodeIdAndStatusOrderByCreatedAtAsc("node-1", TaskStatus.QUEUED)).thenReturn(Optional.of(queued));

        DeferredResult<HeartbeatResponse> result = service.poll("node-1", List.of());

        assertThat(result.hasResult()).isTrue();
        assertThat(((HeartbeatResponse) result.getResult()).task().taskId()).isEqualTo(queued.getId());
        assertThat(queued.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
        assertThat(queued.getAssignedAt()).isNotNull();
    }

    @Test
    void pollParksUntilWorkIsCreated() {
        Task later = Task.scan("node-1", "127.0.0.1");
        when(tasks.findFirstByNodeIdAndStatusOrderByCreatedAtAsc("node-1", TaskStatus.QUEUED))
                .thenReturn(Optional.empty(), Optional.of(later));

        DeferredResult<HeartbeatResponse> result = service.poll("node-1", List.of());
        assertThat(result.hasResult()).isFalse();

        service.createScan("node-1", "127.0.0.1");

        assertThat(result.hasResult()).isTrue();
        assertThat(((HeartbeatResponse) result.getResult()).task().taskId()).isEqualTo(later.getId());
    }

    @Test
    void acknowledgePromotesHeldTasksAndRequeuesStaleOnes() {
        Task held = assigned("node-1", 10);
        Task stale = assigned("node-1", 60);
        Task recent = assigned("node-1", 5);
        when(tasks.findByNodeIdAndStatus("node-1", TaskStatus.ASSIGNED)).thenReturn(List.of(held, stale, recent));

        service.acknowledge("node-1", List.of(held.getId()));

        assertThat(held.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(stale.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(stale.getRetryCount()).isEqualTo(1);
        assertThat(recent.getStatus()).isEqualTo(TaskStatus.ASSIGNED);
    }

    @Test
    void recordResultStoresOutputAndIsIdempotent() {
        Task running = running("node-1");
        when(tasks.findById(running.getId())).thenReturn(Optional.of(running));
        TaskResult result = new TaskResult(TaskStatus.DONE, 0, "<nmaprun/>", null,
                List.of(new Host("127.0.0.1", "up", List.of(new Port(22, "tcp", "open", "ssh")))));

        assertThat(service.recordResult("node-1", running.getId(), result)).isEqualTo(new Ack(1, 0));
        assertThat(running.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(running.getRawOutput()).isEqualTo("<nmaprun/>");
        assertThat(running.getStructuredJson()).contains("\"port\":22");
        assertThat(service.hosts(running)).hasSize(1);

        // The node retries the same POST: nothing changes.
        assertThat(service.recordResult("node-1", running.getId(), result)).isEqualTo(new Ack(0, 1));
        verify(tasks).save(running);
    }

    @Test
    void recordResultRejectsAnotherNodesTask() {
        Task running = running("node-1");
        when(tasks.findById(running.getId())).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> service.recordResult("node-2", running.getId(),
                new TaskResult(TaskStatus.DONE, 0, "", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(tasks, never()).save(any());
    }

    @Test
    void interruptedTaskIsRequeuedOnceThenFailed() {
        Task task = running("node-1");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        TaskResult interrupted = new TaskResult(TaskStatus.INTERRUPTED, null, null, "node restarted", null);

        service.recordResult("node-1", task.getId(), interrupted);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.getRetryCount()).isEqualTo(1);

        // A repeated report while re-queued is a duplicate, not another retry.
        assertThat(service.recordResult("node-1", task.getId(), interrupted)).isEqualTo(new Ack(0, 1));

        task.setStatus(TaskStatus.RUNNING);
        service.recordResult("node-1", task.getId(), interrupted);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getError()).contains("interrupted");
    }

    private static Task assigned(String nodeId, int secondsAgo) {
        Task t = Task.scan(nodeId, "127.0.0.1");
        t.setStatus(TaskStatus.ASSIGNED);
        t.setAssignedAt(Instant.now().minusSeconds(secondsAgo));
        return t;
    }

    private static Task running(String nodeId) {
        Task t = assigned(nodeId, 10);
        t.setStatus(TaskStatus.RUNNING);
        return t;
    }
}

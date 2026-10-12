package org.example.scanning.manager;

import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Heartbeat;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.manager.model.AuditEvent;
import org.example.scanning.manager.model.Task;
import org.example.scanning.manager.repository.AuditEventRepository;
import org.example.scanning.manager.service.NodeService;
import org.example.scanning.manager.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real services on in-memory H2 (no web server, so no keystore is needed). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:manager-test;DB_CLOSE_DELAY=-1",
        "scanning.enrollment-token=test-token"
})
class ManagerApplicationTests {

    @Autowired
    private NodeService nodes;
    @Autowired
    private TaskService tasks;
    @Autowired
    private AuditEventRepository events;

    @Test
    void enrollScanDeliverResultFlow() {
        EnrollResponse enrolled = nodes.enroll(new EnrollRequest("test-token", "it-node", "vm", "ubuntu", "1.0.0", "7.80"));
        String auth = "Bearer it-node:" + enrolled.secret();
        assertThat(nodes.authenticate(auth, "it-node")).isEqualTo("it-node");
        assertThat(nodes.isOnline(nodes.get("it-node"))).isTrue();

        Task scan = tasks.createScan("it-node", "127.0.0.1");

        nodes.heartbeat("it-node", new Heartbeat("vm", "ubuntu", "1.0.0", "7.80", List.of()));
        DeferredResult<HeartbeatResponse> poll = tasks.poll("it-node", List.of());
        assertThat(((HeartbeatResponse) poll.getResult()).task().taskId()).isEqualTo(scan.getId());
        assertThat(tasks.get(scan.getId()).getStatus()).isEqualTo(TaskStatus.ASSIGNED);

        // Next heartbeat confirms the node holds it.
        tasks.poll("it-node", List.of(scan.getId()));
        assertThat(tasks.get(scan.getId()).getStatus()).isEqualTo(TaskStatus.RUNNING);

        TaskResult result = new TaskResult(TaskStatus.DONE, 0, "<nmaprun/>", null, List.of());
        assertThat(tasks.recordResult("it-node", scan.getId(), result)).isEqualTo(new Ack(1, 0));
        assertThat(tasks.recordResult("it-node", scan.getId(), result)).isEqualTo(new Ack(0, 1));
        assertThat(tasks.get(scan.getId()).getRawOutput()).isEqualTo("<nmaprun/>");
    }

    @Test
    void duplicateEventIdIsRejectedByTheDatabase() {
        events.saveAndFlush(new AuditEvent("dup-1", "n1", "INSTALLED", null, Instant.now()));

        assertThatThrownBy(() -> events.saveAndFlush(new AuditEvent("dup-1", "n1", "INSTALLED", null, Instant.now())))
                .isInstanceOf(RuntimeException.class);
    }
}

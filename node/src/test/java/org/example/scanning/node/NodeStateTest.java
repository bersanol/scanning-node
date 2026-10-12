package org.example.scanning.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.scanning.common.Protocol;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.common.Protocol.TaskType;
import org.example.scanning.node.NodeState.Identity;
import org.example.scanning.node.NodeState.UpgradeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeStateTest {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void identitySurvivesReopen(@TempDir Path dir) throws Exception {
        new NodeState(dir, JSON).saveIdentity(new Identity("node-1", "s3cret"));

        assertThat(new NodeState(dir, JSON).identity()).contains(new Identity("node-1", "s3cret"));
    }

    @Test
    void identityFileIsOwnerOnly(@TempDir Path dir) throws Exception {
        new NodeState(dir, JSON).saveIdentity(new Identity("node-1", "s3cret"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("identity.json"))))
                .isEqualTo("rw-------");
    }

    @Test
    void acceptedTasksAreHeldUntilRemoved(@TempDir Path dir) throws Exception {
        NodeState state = new NodeState(dir, JSON);
        Protocol.Task task = new Protocol.Task("t1", TaskType.SCAN, "10.0.0.0/24", null, null, null, null);

        state.acceptTask(task);
        assertThat(state.heldTasks()).containsExactly(task);

        state.removeTask("t1");
        assertThat(state.heldTasks()).isEmpty();
    }

    @Test
    void outboxKeepsResultsAndEventsUntilDeleted(@TempDir Path dir) throws Exception {
        NodeState state = new NodeState(dir, JSON);
        state.queueResult("t1", new TaskResult(TaskStatus.DONE, 0, "<nmaprun/>", null, List.of()));
        state.queueEvent("INSTALLED", "1.0.0");

        List<Path> results = state.pendingResults();
        assertThat(results).hasSize(1);
        assertThat(NodeState.taskIdOf(results.get(0))).isEqualTo("t1");
        assertThat(state.read(results.get(0), TaskResult.class).orElseThrow().rawOutput()).isEqualTo("<nmaprun/>");

        List<Path> events = state.pendingEvents();
        assertThat(events).hasSize(1);
        Event event = state.read(events.get(0), Event.class).orElseThrow();
        assertThat(event.type()).isEqualTo("INSTALLED");
        assertThat(event.eventId()).isNotBlank();

        state.delete(results.get(0));
        assertThat(state.pendingResults()).isEmpty();
        assertThat(state.pendingEvents()).hasSize(1);
    }

    @Test
    void upgradeStateRoundTripsAndClears(@TempDir Path dir) throws Exception {
        NodeState state = new NodeState(dir, JSON);
        assertThat(state.upgradeState()).isEmpty();

        state.saveUpgradeState(new UpgradeState("1.0.0", "1.1.0", "SWAPPED"));
        Files.writeString(dir.resolve("start-attempts"), "2");
        assertThat(state.upgradeState()).contains(new UpgradeState("1.0.0", "1.1.0", "SWAPPED"));

        state.clearUpgradeState();
        assertThat(state.upgradeState()).isEmpty();
        assertThat(dir.resolve("start-attempts")).doesNotExist();
    }

    @Test
    void writesLeaveNoTempFiles(@TempDir Path dir) throws Exception {
        new NodeState(dir, JSON).saveIdentity(new Identity("n", "s"));
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }
}

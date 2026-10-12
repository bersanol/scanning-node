package org.example.scanning.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.scanning.common.Protocol;
import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.common.Protocol.TaskType;
import org.example.scanning.node.NodeState.Identity;
import org.example.scanning.node.NodeState.UpgradeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTest {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    static final Protocol.Task SCAN = new Protocol.Task("t1", TaskType.SCAN, "127.0.0.1", null, null, null, null);
    static final TaskResult DONE = new TaskResult(TaskStatus.DONE, 0, "<nmaprun/>", null, List.of());

    @Mock
    ManagerClient client;
    @Mock
    Nmap nmap;

    @TempDir
    Path dir;
    NodeState state;
    Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        state = new NodeState(dir.resolve("state"), JSON);
        when(nmap.version()).thenReturn("7.80");
        when(client.enroll(any())).thenReturn(new EnrollResponse("node-1", "s3cret"));
        when(client.postResult(any(), any(), any())).thenReturn(true);
        when(client.postEvents(any(), anyList())).thenReturn(new Ack(1, 0));
        when(client.heartbeat(any(), any())).thenReturn(new HeartbeatResponse(null));
        // Runnable::run makes the worker synchronous so tests are deterministic.
        agent = new Agent(state, client, nmap, Runnable::run, "1.0.0", "enroll-token", dir.resolve("app"));
    }

    @Test
    void recoverReportsInProgressTasksAsInterrupted() {
        state.acceptTask(SCAN);

        agent.recover();

        assertThat(state.heldTasks()).isEmpty();
        List<Path> results = state.pendingResults();
        assertThat(results).hasSize(1);
        assertThat(state.read(results.get(0), TaskResult.class).orElseThrow().status()).isEqualTo(TaskStatus.INTERRUPTED);
        assertThat(state.pendingEvents()).hasSize(1); // STARTED
    }

    @Test
    void firstTickEnrollsThenAcceptsRunsAndDeliversScan() throws Exception {
        when(client.heartbeat(any(), any())).thenReturn(new HeartbeatResponse(SCAN), new HeartbeatResponse(null));
        when(nmap.scan("127.0.0.1")).thenReturn(DONE);

        agent.tick();

        assertThat(state.identity()).contains(new Identity("node-1", "s3cret"));
        verify(nmap).scan("127.0.0.1");
        assertThat(state.heldTasks()).isEmpty();            // finished, removed from tasks/
        verify(client).postResult(eq(new Identity("node-1", "s3cret")), eq("t1"), eq(DONE));
        assertThat(state.pendingResults()).isEmpty();
    }

    @Test
    void heldTaskIsReportedInHeartbeatAndNotAcceptedTwice() throws Exception {
        state.saveIdentity(new Identity("node-1", "s3cret"));
        state.acceptTask(SCAN);
        when(client.heartbeat(any(), any())).thenReturn(new HeartbeatResponse(SCAN));

        agent.tick();

        ArgumentCaptor<Protocol.Heartbeat> hb = ArgumentCaptor.forClass(Protocol.Heartbeat.class);
        verify(client).heartbeat(any(), hb.capture());
        assertThat(hb.getValue().heldTaskIds()).containsExactly("t1");
        assertThat(hb.getValue().appVersion()).isEqualTo("1.0.0");
        verify(nmap, never()).scan(any());
    }

    @Test
    void resultsSurviveManagerOutageAndAreDeliveredOnce() throws Exception {
        state.saveIdentity(new Identity("node-1", "s3cret"));
        state.queueResult("t1", DONE);
        when(client.postResult(any(), any(), any())).thenThrow(new IOException("connection refused"));

        agent.tick();
        assertThat(state.pendingResults()).hasSize(1);
        verify(client, times(1)).heartbeat(any(), any());   // the heartbeat still went out first

        // Backoff: an immediate retry is skipped without touching the network.
        agent.tick();
        verify(client, times(1)).postResult(any(), any(), any());
    }

    @Test
    void completedUpgradeIsReportedAfterFirstHeartbeat() throws Exception {
        state.saveIdentity(new Identity("node-1", "s3cret"));
        state.saveUpgradeState(new UpgradeState("0.9.0", "1.0.0", "SWAPPED"));

        agent.tick();

        assertThat(state.upgradeState()).isEmpty();
        List<Event> events = deliveredEvents();
        assertThat(events).extracting(Event::type).containsExactly("UPGRADE_COMPLETED");
        assertThat(events.get(0).detail()).isEqualTo("0.9.0 -> 1.0.0");
    }

    @Test
    void rolledBackUpgradeIsReported() throws Exception {
        state.saveIdentity(new Identity("node-1", "s3cret"));
        state.saveUpgradeState(new UpgradeState("1.0.0", "1.1.0", "ROLLED_BACK"));

        agent.tick();

        assertThat(deliveredEvents()).extracting(Event::type).containsExactly("UPGRADE_ROLLED_BACK");
    }

    @Test
    void resultTheManagerRejectsForGoodIsDroppedAndDoesNotBlockHeartbeats() throws Exception {
        state.saveIdentity(new Identity("node-1", "s3cret"));
        state.queueResult("gone", DONE);
        when(client.postResult(any(), eq("gone"), any())).thenReturn(false);

        agent.tick();
        agent.tick();

        assertThat(state.pendingResults()).isEmpty();
        verify(client, times(1)).postResult(any(), eq("gone"), any());
        verify(client, times(2)).heartbeat(any(), any());
    }

    /** Events posted to the manager during the tick (the outbox is flushed after the heartbeat). */
    @SuppressWarnings("unchecked")
    private List<Event> deliveredEvents() throws Exception {
        ArgumentCaptor<List<Event>> sent = ArgumentCaptor.forClass(List.class);
        verify(client).postEvents(any(), sent.capture());
        return sent.getValue();
    }

    @Test
    void checksumMismatchFailsUpgradeWithoutSwapping() throws Exception {
        state.saveIdentity(new Identity("node-1", "s3cret"));
        Protocol.Task upgrade = new Protocol.Task("u1", TaskType.UPGRADE, null, "app", "1.1.0", "/releases/node-1.1.0.jar", "deadbeef");
        when(client.heartbeat(any(), any())).thenReturn(new HeartbeatResponse(upgrade));
        org.mockito.Mockito.doAnswer(inv -> {
            java.nio.file.Files.createDirectories(inv.getArgument(1, Path.class).getParent());
            java.nio.file.Files.writeString(inv.getArgument(1, Path.class), "not the real jar");
            return null;
        }).when(client).download(any(), any());

        agent.tick();

        ArgumentCaptor<TaskResult> sent = ArgumentCaptor.forClass(TaskResult.class);
        verify(client).postResult(any(), eq("u1"), sent.capture());
        TaskResult result = sent.getValue();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error()).contains("checksum mismatch");
        assertThat(dir.resolve("app/current")).doesNotExist();
        assertThat(state.upgradeState()).isEmpty();
    }
}

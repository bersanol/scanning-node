package org.example.scanning.manager.service;

import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.manager.model.Node;
import org.example.scanning.manager.repository.AuditEventRepository;
import org.example.scanning.manager.repository.NodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeServiceTest {

    @Mock
    private NodeRepository nodes;
    @Mock
    private AuditEventRepository events;

    private NodeService service;

    @BeforeEach
    void setUp() {
        service = new NodeService(nodes, events, "enroll-secret", Duration.ofSeconds(45));
    }

    @Test
    void enrollStoresHashNotSecret() {
        when(nodes.existsById("node-1")).thenReturn(false);

        EnrollResponse response = service.enroll(new EnrollRequest("enroll-secret", "node-1", "vm-a", "ubuntu", "1.0.0", "7.80"));

        ArgumentCaptor<Node> saved = ArgumentCaptor.forClass(Node.class);
        verify(nodes).save(saved.capture());
        assertThat(response.secret()).isNotBlank();
        assertThat(saved.getValue().getSecretHash()).isEqualTo(NodeService.sha256(response.secret()));
        assertThat(saved.getValue().getHostname()).isEqualTo("vm-a");
    }

    @Test
    void enrollRejectsWrongToken() {
        assertThatThrownBy(() -> service.enroll(new EnrollRequest("wrong", "node-1", null, null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(nodes, never()).save(any());
    }

    @Test
    void reEnrollOfExistingIdIsRejectedSoTheNodeCannotBeTakenOver() {
        when(nodes.existsById("node-1")).thenReturn(true);

        assertThatThrownBy(() -> service.enroll(new EnrollRequest("enroll-secret", "node-1", "vm-a", null, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(nodes, never()).save(any());
    }

    @Test
    void authenticateChecksCredentialsAndNodeOwnership() {
        when(nodes.findById("node-1")).thenReturn(Optional.of(new Node("node-1", NodeService.sha256("s3cret"))));

        assertThat(service.authenticate("Bearer node-1:s3cret", "node-1")).isEqualTo("node-1");
        assertThat(service.authenticate("Bearer node-1:s3cret", null)).isEqualTo("node-1");

        assertStatus(() -> service.authenticate("Bearer node-1:wrong", "node-1"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> service.authenticate(null, "node-1"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> service.authenticate("Bearer garbage", "node-1"), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> service.authenticate("Bearer node-1:s3cret", "node-2"), HttpStatus.FORBIDDEN);
    }

    @Test
    void availabilityIsThreeMissedHeartbeats() {
        Node fresh = new Node("a", "h");
        fresh.setLastSeen(Instant.now().minusSeconds(40));
        Node stale = new Node("b", "h");
        stale.setLastSeen(Instant.now().minusSeconds(50));

        assertThat(service.isOnline(fresh)).isTrue();
        assertThat(service.isOnline(stale)).isFalse();
        assertThat(service.isOnline(new Node("c", "h"))).isFalse();
    }

    @Test
    void eventsAreDeduplicatedByEventId() {
        when(events.existsByEventId("e1")).thenReturn(false);
        when(events.existsByEventId("e2")).thenReturn(true);

        Ack ack = service.recordEvents("node-1", List.of(
                new Event("e1", "INSTALLED", "1.0.0", Instant.now()),
                new Event("e2", "STARTED", null, Instant.now()),
                new Event("", "STARTED", null, Instant.now())));

        assertThat(ack).isEqualTo(new Ack(1, 2));
        verify(events, times(1)).save(any());
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }
}

package org.example.scanning.manager.service;

import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.Heartbeat;
import org.example.scanning.manager.model.AuditEvent;
import org.example.scanning.manager.model.Node;
import org.example.scanning.manager.repository.AuditEventRepository;
import org.example.scanning.manager.repository.NodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Node identity: enrollment, per-request authentication, liveness and the audit trail. */
@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);

    private final NodeRepository nodes;
    private final AuditEventRepository events;
    private final String enrollmentToken;
    private final Duration offlineAfter;

    public NodeService(NodeRepository nodes, AuditEventRepository events,
                       @Value("${scanning.enrollment-token:}") String enrollmentToken,
                       @Value("${scanning.offline-after:60s}") Duration offlineAfter) {
        this.nodes = nodes;
        this.events = events;
        this.offlineAfter = offlineAfter;
        if (enrollmentToken == null || enrollmentToken.isBlank()) {
            this.enrollmentToken = randomSecret();
            log.warn("No scanning.enrollment-token configured; generated for this run: {}", this.enrollmentToken);
        } else {
            this.enrollmentToken = enrollmentToken;
        }
    }

    public String enrollmentToken() {
        return enrollmentToken;
    }

    /**
     * Creates the node and returns its secret once. An existing ID is rejected (409): otherwise anyone
     * holding the shared enrollment token could re-enroll a known node ID and lock the real node out.
     */
    @Transactional
    public EnrollResponse enroll(EnrollRequest req) {
        if (!MessageDigest.isEqual(bytes(req.enrollmentToken()), bytes(enrollmentToken))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid enrollment token");
        }
        if (nodes.existsById(req.nodeId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "node " + req.nodeId() + " is already enrolled");
        }
        String secret = randomSecret();
        Node node = new Node(req.nodeId(), sha256(secret));
        node.setHostname(req.hostname());
        node.setOs(req.os());
        node.setAppVersion(req.appVersion());
        node.setNmapVersion(req.nmapVersion());
        node.setLastSeen(Instant.now());
        nodes.save(node);
        log.info("Enrolled node {} ({})", node.getId(), node.getHostname());
        return new EnrollResponse(node.getId(), secret);
    }

    /**
     * Validates {@code Authorization: Bearer <nodeId>:<secret>} and returns the node ID.
     * 401 if the credentials are wrong; 403 if they belong to a different node than {@code pathNodeId}.
     */
    public String authenticate(String authorization, String pathNodeId) {
        String creds = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
        int sep = creds.indexOf(':');
        if (sep <= 0 || sep == creds.length() - 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing or malformed bearer credentials");
        }
        String nodeId = creds.substring(0, sep);
        String secret = creds.substring(sep + 1);
        Node node = nodes.findById(nodeId).orElse(null);
        if (node == null || !MessageDigest.isEqual(bytes(sha256(secret)), bytes(node.getSecretHash()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid node credentials");
        }
        if (pathNodeId != null && !pathNodeId.equals(nodeId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "credentials do not match node " + pathNodeId);
        }
        return nodeId;
    }

    @Transactional
    public void heartbeat(String nodeId, Heartbeat hb) {
        Node node = get(nodeId);
        if (hb.hostname() != null) node.setHostname(hb.hostname());
        if (hb.os() != null) node.setOs(hb.os());
        if (hb.appVersion() != null) node.setAppVersion(hb.appVersion());
        if (hb.nmapVersion() != null) node.setNmapVersion(hb.nmapVersion());
        node.setLastSeen(Instant.now());
        nodes.save(node);
    }

    /** Availability rule: online if a heartbeat arrived within {@code scanning.offline-after} (60 s = one 40 s cycle + margin). */
    public boolean isOnline(Node node) {
        return node.getLastSeen() != null && node.getLastSeen().plus(offlineAfter).isAfter(Instant.now());
    }

    public Node get(String nodeId) {
        return nodes.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "node " + nodeId));
    }

    public List<Node> all() {
        return nodes.findAllByOrderByEnrolledAtAsc();
    }

    public long onlineCount() {
        return all().stream().filter(this::isOnline).count();
    }

    /** Stores unseen events; replays of the same eventId count as duplicates. */
    @Transactional
    public Ack recordEvents(String nodeId, List<Event> batch) {
        int accepted = 0;
        int duplicates = 0;
        for (Event e : batch) {
            if (e.eventId() == null || e.eventId().isBlank() || e.type() == null || events.existsByEventId(e.eventId())) {
                duplicates++;
                continue;
            }
            events.save(new AuditEvent(e.eventId(), nodeId, e.type(), e.detail(),
                    e.occurredAt() == null ? Instant.now() : e.occurredAt()));
            accepted++;
        }
        return new Ack(accepted, duplicates);
    }

    public List<AuditEvent> events(String nodeId) {
        return events.findByNodeIdOrderByOccurredAtDesc(nodeId);
    }

    public List<AuditEvent> allEvents() {
        return events.findAllByOrderByOccurredAtDesc();
    }

    static String randomSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}

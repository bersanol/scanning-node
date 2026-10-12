package org.example.scanning.node;

import jakarta.annotation.PostConstruct;
import org.example.scanning.common.Protocol;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.Heartbeat;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.common.Protocol.TaskType;
import org.example.scanning.node.NodeState.Identity;
import org.example.scanning.node.NodeState.UpgradeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The node's control loop. Every tick: deliver anything in the outbox, heartbeat (long-poll),
 * and accept at most one task. Accepted tasks are written to disk <em>before</em> they are
 * acknowledged or started, so a crash can only ever lose work the manager will re-send.
 */
@Component
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final NodeState state;
    private final ManagerClient client;
    private final Nmap nmap;
    private final Executor worker;
    private final String appVersion;
    private final String enrollmentToken;
    private final Path appDir;

    private volatile String nmapVersion = "unknown";
    private volatile boolean restartRequested;
    private int failures;
    private Instant retryAt = Instant.MIN;

    public Agent(NodeState state, ManagerClient client, Nmap nmap, Executor worker,
                 @Value("${node.version}") String appVersion,
                 @Value("${scanning.enrollment-token:}") String enrollmentToken,
                 @Value("${scanning.app-dir:/opt/scanning-node}") Path appDir) {
        this.state = state;
        this.client = client;
        this.nmap = nmap;
        this.worker = worker;
        this.appVersion = appVersion;
        this.enrollmentToken = enrollmentToken;
        this.appDir = appDir;
    }

    /** Boot recovery: anything that was running when we died gets an explicit outcome. */
    @PostConstruct
    public void recover() {
        nmapVersion = nmap.version();
        for (Protocol.Task task : state.heldTasks()) {
            log.warn("Task {} was in progress at shutdown; reporting INTERRUPTED", task.taskId());
            state.queueResult(task.taskId(), new TaskResult(TaskStatus.INTERRUPTED, null, null, "node restarted during task", List.of()));
            state.removeTask(task.taskId());
        }
        state.queueEvent("STARTED", "app " + appVersion + ", nmap " + nmapVersion);
        log.info("Node agent {} starting (nmap {}), state in {}", appVersion, nmapVersion, state.dir());
    }

    @Scheduled(fixedDelayString = "${scanning.heartbeat-ms:10000}")
    public void tick() {
        if (Instant.now().isBefore(retryAt)) {
            return;
        }
        try {
            Identity id = ensureEnrolled();
            List<String> held = state.heldTasks().stream().map(Protocol.Task::taskId).toList();
            HeartbeatResponse response = client.heartbeat(id, new Heartbeat(hostname(), os(), appVersion, nmapVersion, held));
            finishUpgradeIfAny();
            failures = 0;
            if (response.task() != null && !held.contains(response.task().taskId())) {
                accept(response.task());
            }
            // After the heartbeat, so an undeliverable outbox can never make the node look offline.
            flushOutbox(id);
        } catch (Exception e) {
            // Exponential backoff with jitter: 10 s, 20 s, ... capped at 5 min, so 3,000 nodes do not
            // hammer a manager that is coming back up.
            failures++;
            long delayMs = Math.min(10_000L << Math.min(failures - 1, 5), 300_000L);
            delayMs += ThreadLocalRandom.current().nextLong(delayMs / 4 + 1);
            retryAt = Instant.now().plusMillis(delayMs);
            log.warn("Manager unreachable ({}); retrying in {} s", e.getMessage(), delayMs / 1000);
        }
    }

    private Identity ensureEnrolled() throws IOException, InterruptedException {
        Identity existing = state.identity().orElse(null);
        if (existing != null) {
            return existing;
        }
        String nodeId = UUID.randomUUID().toString();
        EnrollResponse response = client.enroll(new EnrollRequest(enrollmentToken, nodeId, hostname(), os(), appVersion, nmapVersion));
        Identity id = new Identity(response.nodeId(), response.secret());
        state.saveIdentity(id);
        log.info("Enrolled as node {}", nodeId);
        return id;
    }

    /** Delivers queued results and events; each file is deleted only after the manager acknowledged it. */
    private void flushOutbox(Identity id) throws IOException, InterruptedException {
        for (Path file : state.pendingResults()) {
            TaskResult result = state.read(file, TaskResult.class).orElseThrow();
            if (!client.postResult(id, NodeState.taskIdOf(file), result)) {
                log.warn("Manager rejected the result of task {} for good (unknown task or not ours); dropping it",
                        NodeState.taskIdOf(file));
            }
            state.delete(file);
        }
        List<Path> eventFiles = state.pendingEvents();
        if (!eventFiles.isEmpty()) {
            List<Event> events = eventFiles.stream().map(f -> state.read(f, Event.class).orElseThrow()).toList();
            client.postEvents(id, events);
            eventFiles.forEach(state::delete);
        }
    }

    private void accept(Protocol.Task task) {
        state.acceptTask(task);
        log.info("Accepted {} task {}", task.type(), task.taskId());
        worker.execute(() -> {
            TaskResult result;
            try {
                result = task.type() == TaskType.SCAN ? nmap.scan(task.target()) : upgrade(task);
            } catch (Exception e) {
                result = new TaskResult(TaskStatus.FAILED, null, null, e.toString(), List.of());
            }
            state.queueResult(task.taskId(), result);
            state.removeTask(task.taskId());
            log.info("Task {} finished: {}", task.taskId(), result.status());
            if (restartRequested) {
                log.info("Exiting so the service manager restarts the new release");
                System.exit(0);
            }
        });
    }

    // --- upgrades ---

    private TaskResult upgrade(Protocol.Task task) throws IOException, InterruptedException {
        return "nmap".equals(task.component()) ? upgradeNmap(task) : upgradeApp(task);
    }

    /**
     * Download → verify SHA-256 → atomically repoint {@code current} → record SWAPPED → exit.
     * systemd (via node-start-with-rollback.sh) starts the new JAR; that script rolls back if it fails to start 3 times.
     * The new version reports UPGRADE_COMPLETED on its first successful heartbeat.
     */
    private TaskResult upgradeApp(Protocol.Task task) throws IOException, InterruptedException {
        Path jar = appDir.resolve("releases").resolve(task.targetVersion()).resolve("node.jar");
        client.download(task.artifactUrl(), jar);
        String actual = sha256(jar);
        if (!actual.equalsIgnoreCase(task.sha256())) {
            Files.delete(jar);
            return failed("checksum mismatch for " + task.artifactUrl() + ": expected " + task.sha256() + ", got " + actual);
        }
        Path current = appDir.resolve("current");
        Path tmpLink = appDir.resolve("current.tmp");
        Files.deleteIfExists(tmpLink);
        Files.createSymbolicLink(tmpLink, jar);
        Files.move(tmpLink, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        state.saveUpgradeState(new UpgradeState(appVersion, task.targetVersion(), "SWAPPED"));
        state.queueEvent("UPGRADE_STARTED", appVersion + " -> " + task.targetVersion());
        restartRequested = true;
        return new TaskResult(TaskStatus.DONE, 0, "installed " + jar + "\nsha256 " + actual, null, List.of());
    }

    /** Download → verify → install with the OS package manager (resolves dependencies) → re-read version. */
    private TaskResult upgradeNmap(Protocol.Task task) throws IOException, InterruptedException {
        boolean rpm = task.artifactUrl().endsWith(".rpm");
        Path pkg = Files.createTempFile("nmap-", rpm ? ".rpm" : ".deb");
        client.download(task.artifactUrl(), pkg);
        String actual = sha256(pkg);
        if (!actual.equalsIgnoreCase(task.sha256())) {
            Files.delete(pkg);
            return failed("checksum mismatch for " + task.artifactUrl());
        }
        String[] cmd = rpm ? new String[]{"dnf", "install", "-y", pkg.toString()}
                : new String[]{"apt-get", "install", "-y", "--allow-downgrades", pkg.toString()};
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return failed("package install timed out");
        }
        Files.deleteIfExists(pkg);
        String before = nmapVersion;
        nmapVersion = nmap.version();
        if (p.exitValue() != 0) {
            return new TaskResult(TaskStatus.FAILED, p.exitValue(), output, "package install failed", List.of());
        }
        state.queueEvent("NMAP_UPGRADED", before + " -> " + nmapVersion);
        return new TaskResult(TaskStatus.DONE, 0, output, null, List.of());
    }

    /** Called after a successful heartbeat: a SWAPPED upgrade that is now running has completed. */
    private void finishUpgradeIfAny() {
        state.upgradeState().ifPresent(u -> {
            if ("SWAPPED".equals(u.phase()) && u.to().equals(appVersion)) {
                state.queueEvent("UPGRADE_COMPLETED", u.from() + " -> " + u.to());
            } else {
                state.queueEvent("UPGRADE_ROLLED_BACK", u.to() + " failed to start; running " + appVersion);
            }
            state.clearUpgradeState();
        });
    }

    // --- helpers ---

    private static TaskResult failed(String error) {
        return new TaskResult(TaskStatus.FAILED, null, null, error, List.of());
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv().getOrDefault("HOSTNAME", "unknown");
        }
    }

    private static String os() {
        try {
            for (String line : Files.readAllLines(Path.of("/etc/os-release"))) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return line.substring("PRETTY_NAME=".length()).replace("\"", "");
                }
            }
        } catch (IOException ignored) {
            // not Linux
        }
        return System.getProperty("os.name") + " " + System.getProperty("os.version");
    }
}

package org.example.scanning.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.scanning.common.Protocol;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.TaskResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Everything the node must not lose, as small JSON files under one directory. Every write goes to
 * a temp file and is renamed into place, so a crash never leaves a half-written file.
 *
 * <pre>
 *   identity.json              nodeId + secret, mode 0600 (survives reinstall and upgrades)
 *   tasks/&lt;taskId&gt;.json        tasks accepted but not finished (reported INTERRUPTED after a crash)
 *   outbox/result-&lt;taskId&gt;.json  finished results not yet delivered
 *   outbox/event-&lt;eventId&gt;.json  audit events not yet delivered
 *   upgrade.json               in-flight upgrade: from, to, phase (SWAPPED | ROLLED_BACK)
 * </pre>
 */
public class NodeState {

    public record Identity(String nodeId, String secret) {
    }

    public record UpgradeState(String from, String to, String phase) {
    }

    private final Path dir;
    private final Path tasks;
    private final Path outbox;
    private final ObjectMapper json;

    public NodeState(Path dir, ObjectMapper json) throws IOException {
        this.dir = dir;
        this.tasks = dir.resolve("tasks");
        this.outbox = dir.resolve("outbox");
        this.json = json;
        Files.createDirectories(tasks);
        Files.createDirectories(outbox);
    }

    public Path dir() {
        return dir;
    }

    // --- identity ---

    public Optional<Identity> identity() {
        return read(dir.resolve("identity.json"), Identity.class);
    }

    /** Owner-only (0600): the file holds the node's secret in plaintext. */
    public void saveIdentity(Identity identity) {
        write(dir.resolve("identity.json"), identity, true);
    }

    // --- accepted tasks ---

    public void acceptTask(Protocol.Task task) {
        write(tasks.resolve(task.taskId() + ".json"), task);
    }

    public List<Protocol.Task> heldTasks() {
        return list(tasks, "").stream().map(p -> read(p, Protocol.Task.class).orElseThrow()).toList();
    }

    public void removeTask(String taskId) {
        delete(tasks.resolve(taskId + ".json"));
    }

    // --- outbox ---

    public void queueResult(String taskId, TaskResult result) {
        write(outbox.resolve("result-" + taskId + ".json"), result);
    }

    public void queueEvent(String type, String detail) {
        String id = UUID.randomUUID().toString();
        write(outbox.resolve("event-" + id + ".json"), new Event(id, type, detail, Instant.now()));
    }

    /** Undelivered results; the task ID is in the file name. */
    public List<Path> pendingResults() {
        return list(outbox, "result-");
    }

    public List<Path> pendingEvents() {
        return list(outbox, "event-");
    }

    public static String taskIdOf(Path resultFile) {
        String name = resultFile.getFileName().toString();
        return name.substring("result-".length(), name.length() - ".json".length());
    }

    // --- upgrade ---

    public Optional<UpgradeState> upgradeState() {
        return read(dir.resolve("upgrade.json"), UpgradeState.class);
    }

    public void saveUpgradeState(UpgradeState state) {
        write(dir.resolve("upgrade.json"), state);
    }

    public void clearUpgradeState() {
        delete(dir.resolve("upgrade.json"));
        delete(dir.resolve("start-attempts"));
    }

    // --- file helpers ---

    public <T> Optional<T> read(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(file.toFile(), type));
        } catch (IOException e) {
            throw new UncheckedIOException("corrupt state file " + file, e);
        }
    }

    public void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path target, Object value) {
        write(target, value, false);
    }

    private void write(Path target, Object value, boolean ownerOnly) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            if (ownerOnly) {
                // Create with 0600 before any byte is written, so the secret is never world-readable.
                Files.deleteIfExists(tmp);
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            json.writeValue(tmp.toFile(), value);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    private List<Path> list(Path directory, String prefix) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package org.example.scanning.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.Heartbeat;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.node.NodeState.Identity;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * The node's only way to talk to the manager: outbound HTTPS with the JDK's standard TLS
 * verification. {@code ssl} trusts only the manager certificate packaged in the node JAR, so an
 * impostor fails the handshake.
 */
public class ManagerClient {

    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json;

    public ManagerClient(String baseUrl, ObjectMapper json, SSLContext ssl) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.json = json;
        this.http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
    }

    public EnrollResponse enroll(EnrollRequest request) throws IOException, InterruptedException {
        return post("api/v1/enroll", null, request, EnrollResponse.class, Duration.ofSeconds(20));
    }

    /** Held open by the manager for up to 30 s when no task is queued. */
    public HeartbeatResponse heartbeat(Identity id, Heartbeat heartbeat) throws IOException, InterruptedException {
        return post("api/v1/nodes/" + id.nodeId() + "/heartbeat", id, heartbeat, HeartbeatResponse.class, Duration.ofSeconds(45));
    }

    public Ack postResult(Identity id, String taskId, TaskResult result) throws IOException, InterruptedException {
        return post("api/v1/tasks/" + taskId + "/result", id, result, Ack.class, Duration.ofSeconds(30));
    }

    public Ack postEvents(Identity id, List<Event> events) throws IOException, InterruptedException {
        return post("api/v1/nodes/" + id.nodeId() + "/events", id, events, Ack.class, Duration.ofSeconds(30));
    }

    /** Downloads a release artifact (absolute URL or path relative to the manager) to {@code dest}. */
    public void download(String url, Path dest) throws IOException, InterruptedException {
        Files.createDirectories(dest.toAbsolutePath().getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(base.resolve(url)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException("download " + url + " failed: HTTP " + response.statusCode());
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private <T> T post(String path, Identity id, Object body, Class<T> type, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (id != null) {
            request.header("Authorization", "Bearer " + id.nodeId() + ":" + id.secret());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("POST " + path + " failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return json.readValue(response.body(), type);
    }
}
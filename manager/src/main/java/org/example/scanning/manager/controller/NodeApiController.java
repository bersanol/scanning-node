package org.example.scanning.manager.controller;

import jakarta.validation.Valid;
import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.Event;
import org.example.scanning.common.Protocol.Heartbeat;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.manager.service.NodeService;
import org.example.scanning.manager.service.TaskService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;

/**
 * The four node-initiated endpoints (see {@link org.example.scanning.common.Protocol}). Enrollment
 * is protected by the enrollment token; everything else by per-node bearer credentials, checked
 * explicitly as the first line of each handler.
 */
@RestController
@RequestMapping("/api/v1")
public class NodeApiController {

    private final NodeService nodes;
    private final TaskService tasks;

    public NodeApiController(NodeService nodes, TaskService tasks) {
        this.nodes = nodes;
        this.tasks = tasks;
    }

    @PostMapping("/enroll")
    public EnrollResponse enroll(@Valid @RequestBody EnrollRequest request) {
        return nodes.enroll(request);
    }

    @PostMapping("/nodes/{nodeId}/heartbeat")
    public DeferredResult<HeartbeatResponse> heartbeat(@PathVariable String nodeId,
                                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                                       @RequestBody Heartbeat heartbeat) {
        nodes.authenticate(auth, nodeId);
        nodes.heartbeat(nodeId, heartbeat);
        return tasks.poll(nodeId, heartbeat.held());
    }

    @PostMapping("/nodes/{nodeId}/events")
    public Ack events(@PathVariable String nodeId,
                      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                      @RequestBody List<Event> events) {
        nodes.authenticate(auth, nodeId);
        return nodes.recordEvents(nodeId, events);
    }

    @PostMapping("/tasks/{taskId}/result")
    public Ack result(@PathVariable String taskId,
                      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                      @Valid @RequestBody TaskResult result) {
        String nodeId = nodes.authenticate(auth, null);
        return tasks.recordResult(nodeId, taskId, result);
    }
}

package org.example.scanning.manager.controller;

import org.example.scanning.common.Protocol;
import org.example.scanning.common.Protocol.Ack;
import org.example.scanning.common.Protocol.EnrollRequest;
import org.example.scanning.common.Protocol.EnrollResponse;
import org.example.scanning.common.Protocol.HeartbeatResponse;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskType;
import org.example.scanning.manager.service.NodeService;
import org.example.scanning.manager.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NodeApiController.class)
class NodeApiControllerTest {

    private static final String AUTH = "Bearer node-1:s3cret";
    private static final String RESULT_JSON = """
            {"status":"DONE","exitCode":0,"rawOutput":"<nmaprun/>",
             "hosts":[{"address":"127.0.0.1","state":"up","ports":[{"port":22,"protocol":"tcp","state":"open","service":"ssh"}]}]}
            """;

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private NodeService nodes;
    @MockitoBean
    private TaskService tasks;

    @Test
    void enrollReturnsSecretOr401Or400() throws Exception {
        when(nodes.enroll(any(EnrollRequest.class))).thenReturn(new EnrollResponse("node-1", "s3cret"));
        mvc.perform(post("/api/v1/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentToken\":\"tok\",\"nodeId\":\"node-1\",\"hostname\":\"h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("s3cret"));

        when(nodes.enroll(any(EnrollRequest.class))).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid enrollment token"));
        mvc.perform(post("/api/v1/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentToken\":\"bad\",\"nodeId\":\"node-1\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/enroll").contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"h\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void heartbeatRequiresValidCredentialsForThatNode() throws Exception {
        when(nodes.authenticate(isNull(), eq("node-1"))).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing"));
        mvc.perform(post("/api/v1/nodes/node-1/heartbeat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        when(nodes.authenticate(AUTH, "node-2")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "not yours"));
        mvc.perform(post("/api/v1/nodes/node-2/heartbeat").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void heartbeatReturnsAssignedTaskAsynchronously() throws Exception {
        DeferredResult<HeartbeatResponse> deferred = new DeferredResult<>();
        deferred.setResult(new HeartbeatResponse(new Protocol.Task("t1", TaskType.SCAN, "10.0.0.0/24", null, null, null, null)));
        when(tasks.poll(eq("node-1"), anyList())).thenReturn(deferred);

        MvcResult started = mvc.perform(post("/api/v1/nodes/node-1/heartbeat").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hostname\":\"h\",\"appVersion\":\"1.0.0\",\"nmapVersion\":\"7.80\",\"heldTaskIds\":[]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value("t1"))
                .andExpect(jsonPath("$.task.type").value("SCAN"))
                .andExpect(jsonPath("$.task.target").value("10.0.0.0/24"));
    }

    @Test
    void eventsAreAcknowledged() throws Exception {
        when(nodes.recordEvents(eq("node-1"), anyList())).thenReturn(new Ack(1, 1));

        mvc.perform(post("/api/v1/nodes/node-1/events").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"eventId\":\"e1\",\"type\":\"INSTALLED\",\"occurredAt\":\"2026-10-12T10:00:00Z\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
    }

    @Test
    void resultIsRecordedForAuthenticatedNode() throws Exception {
        when(nodes.authenticate(AUTH, null)).thenReturn("node-1");
        when(tasks.recordResult(eq("node-1"), eq("t1"), any(TaskResult.class))).thenReturn(new Ack(0, 1));

        mvc.perform(post("/api/v1/tasks/t1/result").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content(RESULT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates").value(1));

        mvc.perform(post("/api/v1/tasks/t1/result").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exitCode\":0}"))
                .andExpect(status().isBadRequest());
    }
}

package org.example.scanning.manager.controller;

import org.example.scanning.common.Protocol.TaskStatus;
import org.example.scanning.manager.model.Node;
import org.example.scanning.manager.model.Task;
import org.example.scanning.manager.service.NodeService;
import org.example.scanning.manager.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private NodeService nodes;
    @MockitoBean
    private TaskService tasks;

    @Test
    void indexShowsNodesWithAvailability() throws Exception {
        Node node = new Node("node-1", "hash");
        node.setHostname("vm-a");
        node.setAppVersion("1.0.0");
        node.setNmapVersion("7.80");
        node.setLastSeen(Instant.now());
        when(nodes.page(0, DashboardController.PAGE_SIZE)).thenReturn(new PageImpl<>(List.of(node)));
        when(nodes.count()).thenReturn(1L);
        when(nodes.onlineCount()).thenReturn(1L);
        when(nodes.isOnline(node)).thenReturn(true);
        when(tasks.page(0, DashboardController.PAGE_SIZE)).thenReturn(Page.empty());
        when(nodes.enrollmentToken()).thenReturn("tok-123");

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("vm-a")))
                .andExpect(content().string(containsString(">online<")))
                .andExpect(content().string(containsString("7.80")))
                .andExpect(content().string(containsString("tok-123")));
    }

    @Test
    void largeFleetIsPagedAndCountedByTheDatabase() throws Exception {
        Node node = new Node("node-51", "hash");
        when(nodes.page(1, DashboardController.PAGE_SIZE))
                .thenReturn(new PageImpl<>(List.of(node), PageRequest.of(1, DashboardController.PAGE_SIZE), 3000));
        when(nodes.count()).thenReturn(3000L);
        when(nodes.onlineCount()).thenReturn(2990L);
        when(tasks.page(0, DashboardController.PAGE_SIZE)).thenReturn(Page.empty());

        mvc.perform(get("/").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">3000</strong> registered")))
                .andExpect(content().string(containsString(">10</strong> offline")))
                .andExpect(content().string(containsString("Page <span>2</span> of <span>60</span>")))
                .andExpect(content().string(containsString("/?page=2&amp;taskPage=0")));
    }

    @Test
    void auditIsPaged() throws Exception {
        when(nodes.eventsPage(0, DashboardController.PAGE_SIZE)).thenReturn(Page.empty());

        mvc.perform(get("/audit").param("page", "-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No events yet.")));
    }

    @Test
    void submittingScanRedirectsToTask() throws Exception {
        Task task = Task.scan("node-1", "10.0.0.0/24");
        when(tasks.createScan("node-1", "10.0.0.0/24")).thenReturn(task);

        mvc.perform(post("/scans").param("nodeId", "node-1").param("target", "10.0.0.0/24"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + task.getId()));
    }

    @Test
    void invalidTargetIsBadRequest() throws Exception {
        when(tasks.createScan(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad target"));

        mvc.perform(post("/scans").param("nodeId", "node-1").param("target", "evil.example"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskPageShowsStructuredAndRawOutput() throws Exception {
        Task task = Task.scan("node-1", "127.0.0.1");
        task.setStatus(TaskStatus.DONE);
        task.setRawOutput("<nmaprun scanner=\"nmap\"/>");
        when(tasks.get(task.getId())).thenReturn(task);
        when(tasks.hosts(task)).thenReturn(List.of(new org.example.scanning.common.Protocol.Host("127.0.0.1", "up",
                List.of(new org.example.scanning.common.Protocol.Port(22, "tcp", "open", "ssh")))));

        mvc.perform(get("/tasks/" + task.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ssh")))
                .andExpect(content().string(containsString("&lt;nmaprun")));
    }
}

package org.example.scanning.manager.controller;

import org.example.scanning.manager.model.Node;
import org.example.scanning.manager.model.Task;
import org.example.scanning.manager.service.NodeService;
import org.example.scanning.manager.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Server-rendered operator UI: fleet overview, node detail, task detail, audit history. */
@Controller
public class DashboardController {

    /** A file in the releases directory offered for upgrade. */
    public record Release(String file, String component, String version, String url, String sha256) {
    }

    /** Rows per table page: the fleet (~3,000 nodes) and the task/event history are never loaded whole. */
    static final int PAGE_SIZE = 50;

    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private final NodeService nodes;
    private final TaskService tasks;
    private final Path releasesDir;

    public DashboardController(NodeService nodes, TaskService tasks,
                               @Value("${scanning.releases-dir:releases}") Path releasesDir) {
        this.nodes = nodes;
        this.tasks = tasks;
        this.releasesDir = releasesDir;
    }

    @GetMapping("/")
    public String index(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int taskPage,
                        Model model) {
        Page<Node> nodePage = nodes.page(Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("nodes", nodePage);
        model.addAttribute("online", nodePage.stream().collect(Collectors.toMap(Node::getId, nodes::isOnline)));
        model.addAttribute("registered", nodes.count());
        model.addAttribute("onlineCount", nodes.onlineCount());
        model.addAttribute("tasks", tasks.page(Math.max(taskPage, 0), PAGE_SIZE));
        model.addAttribute("enrollmentToken", nodes.enrollmentToken());
        return "index";
    }

    @GetMapping("/nodes/{nodeId}")
    public String node(@PathVariable String nodeId, Model model) {
        Node node = nodes.get(nodeId);
        model.addAttribute("node", node);
        model.addAttribute("isOnline", nodes.isOnline(node));
        model.addAttribute("tasks", tasks.forNode(nodeId));
        model.addAttribute("events", nodes.events(nodeId));
        model.addAttribute("releases", releases());
        return "node";
    }

    @GetMapping("/tasks/{taskId}")
    public String task(@PathVariable String taskId, Model model) {
        Task task = tasks.get(taskId);
        model.addAttribute("task", task);
        model.addAttribute("hosts", tasks.hosts(task));
        return "task";
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("events", nodes.eventsPage(Math.max(page, 0), PAGE_SIZE));
        return "audit";
    }

    @PostMapping("/scans")
    public String scan(@RequestParam String nodeId, @RequestParam String target) {
        nodes.get(nodeId);
        return "redirect:/tasks/" + tasks.createScan(nodeId, target).getId();
    }

    @PostMapping("/nodes/{nodeId}/upgrade")
    public String upgrade(@PathVariable String nodeId, @RequestParam String artifact) {
        Release release = releases().stream().filter(r -> r.file().equals(artifact)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown release " + artifact));
        tasks.createUpgrade(nodeId, release.component(), release.version(), release.url(), release.sha256());
        return "redirect:/nodes/" + nodeId;
    }

    /** node-*.jar files are app releases; .deb/.rpm files are nmap packages. Version is parsed from the name. */
    List<Release> releases() {
        if (!Files.isDirectory(releasesDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(releasesDir)) {
            return files.filter(Files::isRegularFile).sorted().map(p -> {
                String name = p.getFileName().toString();
                String component = name.endsWith(".jar") ? "app" : (name.endsWith(".deb") || name.endsWith(".rpm")) ? "nmap" : null;
                if (component == null) {
                    return null;
                }
                Matcher m = VERSION.matcher(name);
                String version = m.find() ? m.group(1) : "unknown";
                return new Release(name, component, version, "/releases/" + name, sha256(p));
            }).filter(r -> r != null).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

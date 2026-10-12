package org.example.scanning.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Wires the four node classes: {@link NodeState} (disk), {@link ManagerClient} (HTTPS), {@link Nmap}, {@link Agent}. */
@SpringBootApplication
@EnableScheduling
public class NodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    @Bean
    NodeState nodeState(@Value("${scanning.state-dir:/var/lib/scanning-node}") Path stateDir, ObjectMapper json) throws IOException {
        return new NodeState(stateDir, json);
    }

    @Bean
    ManagerClient managerClient(@Value("${scanning.manager-url:https://localhost:8443}") String url, ObjectMapper json,
                                SslBundles ssl) {
        return new ManagerClient(url, json, ssl.getBundle("manager").createSslContext());
    }

    @Bean
    Nmap nmap(@Value("${scanning.nmap:nmap}") String binary,
              @Value("${scanning.scan-timeout:10m}") Duration timeout) {
        return new Nmap(binary, timeout);
    }

    /** Scans and upgrades run one at a time, off the heartbeat thread, so liveness never stalls. */
    @Bean
    Executor worker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "worker");
            t.setDaemon(true);
            return t;
        });
    }
}

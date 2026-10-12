package org.example.scanning.node;

import org.example.scanning.common.Protocol.Host;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NmapTest {

    static final String SAMPLE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <nmaprun scanner="nmap" args="nmap -oX - 127.0.0.1" version="7.80">
              <host><status state="up" reason="localhost-response"/>
                <address addr="127.0.0.1" addrtype="ipv4"/>
                <ports>
                  <port protocol="tcp" portid="22"><state state="open"/><service name="ssh"/></port>
                  <port protocol="tcp" portid="8443"><state state="closed"/><service name="pcsync-https"/></port>
                </ports>
              </host>
              <host><status state="down"/><address addr="10.0.0.9" addrtype="ipv4"/></host>
            </nmaprun>
            """;

    @Test
    void parsesHostsAndPorts() throws Exception {
        List<Host> hosts = Nmap.parse(SAMPLE_XML);

        assertThat(hosts).hasSize(2);
        assertThat(hosts.get(0).address()).isEqualTo("127.0.0.1");
        assertThat(hosts.get(0).state()).isEqualTo("up");
        assertThat(hosts.get(0).ports()).hasSize(2);
        assertThat(hosts.get(0).ports().get(0).port()).isEqualTo(22);
        assertThat(hosts.get(0).ports().get(0).state()).isEqualTo("open");
        assertThat(hosts.get(0).ports().get(0).service()).isEqualTo("ssh");
        assertThat(hosts.get(1).state()).isEqualTo("down");
        assertThat(hosts.get(1).ports()).isEmpty();
    }

    @Test
    void parsesVersionBanner() {
        assertThat(Nmap.parseVersion("Nmap version 7.80 ( https://nmap.org )\nPlatform: x86_64")).isEqualTo("7.80");
        assertThat(Nmap.parseVersion("command not found")).isEqualTo("unknown");
    }

    @Test
    void scanCapturesRawOutputAndStructuredResult(@TempDir Path dir) throws Exception {
        Path fake = fakeBinary(dir, "printf '%s' '" + SAMPLE_XML.replace("'", "") + "'");

        TaskResult result = new Nmap(fake.toString(), Duration.ofSeconds(10)).scan("127.0.0.1");

        assertThat(result.status()).isEqualTo(TaskStatus.DONE);
        assertThat(result.exitCode()).isZero();
        assertThat(result.rawOutput()).contains("<nmaprun");
        assertThat(result.hosts()).hasSize(2);
    }

    @Test
    void nonZeroExitIsFailedWithStderr(@TempDir Path dir) throws Exception {
        Path fake = fakeBinary(dir, "echo 'Failed to resolve' >&2; exit 1");

        TaskResult result = new Nmap(fake.toString(), Duration.ofSeconds(10)).scan("127.0.0.1");

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.error()).contains("Failed to resolve");
    }

    @Test
    void missingBinaryIsFailedNotCrash() {
        TaskResult result = new Nmap("/nonexistent/nmap", Duration.ofSeconds(1)).scan("127.0.0.1");
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.error()).contains("cannot run nmap");
    }

    private static Path fakeBinary(Path dir, String body) throws Exception {
        Path script = dir.resolve("nmap");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }
}

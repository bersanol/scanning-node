package org.example.scanning.node;

import org.example.scanning.common.Protocol.Host;
import org.example.scanning.common.Protocol.Port;
import org.example.scanning.common.Protocol.TaskResult;
import org.example.scanning.common.Protocol.TaskStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the real nmap binary and turns its XML into structured results. */
public class Nmap {

    private static final Pattern VERSION = Pattern.compile("Nmap version (\\S+)");

    private final String binary;
    private final Duration timeout;

    public Nmap(String binary, Duration timeout) {
        this.binary = binary;
        this.timeout = timeout;
    }

    /** "7.80", or "unknown" if nmap is missing. */
    public String version() {
        try {
            Process p = new ProcessBuilder(binary, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return parseVersion(out);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Scans {@code target}, which the manager has already validated as an IPv4 address or CIDR.
     * Arguments go straight to the process, never through a shell.
     */
    public TaskResult scan(String target) {
        try {
            Process p = new ProcessBuilder(binary, "-oX", "-", target).start();
            byte[] stdout = p.getInputStream().readAllBytes();
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new TaskResult(TaskStatus.FAILED, null, new String(stdout, StandardCharsets.UTF_8),
                        "nmap timed out after " + timeout, List.of());
            }
            String xml = new String(stdout, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                return new TaskResult(TaskStatus.FAILED, p.exitValue(), xml, stderr.isBlank() ? "nmap exit " + p.exitValue() : stderr.trim(), List.of());
            }
            return new TaskResult(TaskStatus.DONE, 0, xml, null, parse(xml));
        } catch (Exception e) {
            return new TaskResult(TaskStatus.FAILED, null, null, "cannot run nmap: " + e, List.of());
        }
    }

    static String parseVersion(String versionOutput) {
        Matcher m = VERSION.matcher(versionOutput);
        return m.find() ? m.group(1) : "unknown";
    }

    /** Extracts host address/state and port number/protocol/state/service from nmap -oX output. */
    static List<Host> parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        List<Host> hosts = new ArrayList<>();
        NodeList hostNodes = doc.getElementsByTagName("host");
        for (int i = 0; i < hostNodes.getLength(); i++) {
            Element host = (Element) hostNodes.item(i);
            String address = null;
            NodeList addresses = host.getElementsByTagName("address");
            for (int a = 0; a < addresses.getLength(); a++) {
                Element addr = (Element) addresses.item(a);
                if (address == null || "ipv4".equals(addr.getAttribute("addrtype"))) {
                    address = addr.getAttribute("addr");
                }
            }
            String state = first(host, "status").map(s -> s.getAttribute("state")).orElse("unknown");
            List<Port> ports = new ArrayList<>();
            NodeList portNodes = host.getElementsByTagName("port");
            for (int p = 0; p < portNodes.getLength(); p++) {
                Element port = (Element) portNodes.item(p);
                ports.add(new Port(Integer.parseInt(port.getAttribute("portid")), port.getAttribute("protocol"),
                        first(port, "state").map(s -> s.getAttribute("state")).orElse("unknown"),
                        first(port, "service").map(s -> s.getAttribute("name")).orElse("")));
            }
            hosts.add(new Host(address, state, ports));
        }
        return hosts;
    }

    private static java.util.Optional<Element> first(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? java.util.Optional.empty() : java.util.Optional.of((Element) list.item(0));
    }
}

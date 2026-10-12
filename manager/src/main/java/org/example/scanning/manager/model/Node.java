package org.example.scanning.manager.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "nodes", indexes = @Index(name = "ix_nodes_last_seen", columnList = "last_seen"))
public class Node {

    @Id
    @Column(length = 64)
    private String id;

    /** SHA-256 of the per-node secret; the secret itself is never stored. */
    @Column(nullable = false)
    private String secretHash;

    private String hostname;
    private String os;
    private String appVersion;
    private String nmapVersion;
    private Instant enrolledAt;
    private Instant lastSeen;

    protected Node() {
    }

    public Node(String id, String secretHash) {
        this.id = id;
        this.secretHash = secretHash;
        this.enrolledAt = Instant.now();
    }

    public String getId() { return id; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getNmapVersion() { return nmapVersion; }
    public void setNmapVersion(String nmapVersion) { this.nmapVersion = nmapVersion; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
}

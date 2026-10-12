# plan.md — Remote Scanning Node

Design and trade-offs for a manager, a dashboard and Linux node agents that run real `nmap` scans.
Designed for ~3,000 nodes; demonstrated with two.

## 1. Components

| Component | Technology | Runs on |
|---|---|---|
| Manager + dashboard | Spring Boot 3, Spring Data JPA, H2, Thymeleaf | HTTPS port 8443 |
| Node agent | Spring Boot 3 (scheduler + `HttpClient`), state in JSON files | Ubuntu / RHEL, systemd |
| Installer | `deploy/install.sh` | clean host, as root |
| Common | the wire protocol (`Protocol.java`) | Maven module |

Compiled with `--release 21` so nodes run on the distro's OpenJDK 21. Java on the node keeps one
toolchain and one test suite; the cost is a ~200 MB JDK install (a Go binary is the production
alternative, §9).

The manager is 11 files: entities `Node`, `Task` (results live on it), `AuditEvent`;
`NodeService` (enrollment, auth, liveness, audit); `TaskService` (queue, long-poll, results,
retries); their three repositories; two thin controllers (node API, dashboard); errors are `ResponseStatusException`.

## 2. Communication: node-initiated HTTPS long-poll

The manager listens on one HTTPS port and never connects to a node. Each node loops:

1. `POST /api/v1/nodes/{id}/heartbeat` with its versions, hostname, OS and held task IDs. The
   response carries at most one pending task; with none queued, the manager holds the request up
   to 30 s, so dispatch is near-instant.
2. Flush the local outbox: results (`POST /api/v1/tasks/{taskId}/result`) and audit events
   (`POST /api/v1/nodes/{id}/events`). Then pause 10 s.

| Option | Verdict |
|---|---|
| **HTTPS long-poll** | **Chosen:** no broker, passes egress proxies, stateless manager, natural idempotent retries |
| WebSocket | Stateful connections complicate load balancing; no gain at a 10 s cadence |
| MQTT | Another broker to run and secure |
| Reverse SSH tunnel | Gives the manager a control channel into the node |

**Online** = heartbeat within the last 60 s. A normal idle cycle is up to 40 s (30 s hold + 10 s
pause), so 60 s means a missed cycle. Computed at read time, so a manager restart causes no false
offlines.

## 3. Authentication

**Node → manager.** The operator passes the dashboard's enrollment token to `install.sh`. On first
start the node calls `POST /api/v1/enroll` with a new UUID; the manager returns a random per-node
secret and stores only its SHA-256 hash (fine for 256 random bits). An already-known ID is rejected
(409), so the shared token cannot hijack an existing node. The node keeps its identity in
`identity.json` (0600) and sends `Authorization: Bearer <nodeId>:<secret>`; each handler calls
`nodes.authenticate(...)` first, and a node may act only on its own ID. Reinstalling keeps the
file, so the identity survives.

The secret is a plaintext file because an unattended node must reconnect after a reboot without a
human; encrypting it just moves the key to disk (like SSH keys or kubelet certs). Root on the host
already owns the node, and a stolen secret impersonates only that node. **Production:** per-node
mTLS certificates, ideally with a TPM-bound key.

**Manager → node.** One self-signed key pair, made once by `deploy/generate-tls-keys.sh` and packaged
in the JARs as Spring SSL bundles (`manager.p12` on the manager, `truststore.p12` on the node).
Standard JDK certificate and hostname checks run on every connection; an impostor manager fails the
handshake. No custom TLS code, nothing generated at install time. The SAN covers `localhost`,
`manager`, `host.docker.internal`, `127.0.0.1`. **Production:** a CA-signed certificate, so it can
rotate without a node release.

Rejected: importing a per-deployment cert at install (more moving parts, no gain); plain HTTP with
signed bodies (no channel security); per-node mTLS now (needs a CA and issuance flow; §9).

## 4. Scans

- Dashboard: IP/CIDR + node → task `QUEUED`. The node writes `tasks/<taskId>.json` before
  acknowledging it.
- Runs `nmap -oX - <target>` via `ProcessBuilder` with a 10-minute timeout; the target is
  validated as IPv4/CIDR and never goes through a shell.
- Result (exit code, raw output, hosts/ports/services parsed from the XML) goes to the outbox,
  then is POSTed. A result for an already-terminal task is a duplicate: 200, no change.

```
QUEUED → ASSIGNED → RUNNING → DONE | FAILED | INTERRUPTED
```

`INTERRUPTED` (node died mid-scan) is re-queued once, then `FAILED`.

## 5. Dashboard

`/` counts and node table · `/nodes/{id}` detail, history, audit, upgrades · `/scans`,
`/scans/{id}` status, results, raw output · `/scans/new` · `/audit`. Plain HTML forms.

## 6. Remote upgrades

### Application

**Delivery (pull only).** The manager serves `releases/` at `/releases/`; the dashboard lists each
`node-<version>.jar` with its SHA-256. Clicking upgrade queues a task with the URL, target version
and checksum, which the node picks up in a heartbeat like a scan. The node then downloads the JAR
with its own HTTPS `GET`; the file arrives as the response body, so the manager never opens a
connection (it is a passive file server, like any website).

**Steps on the node:**

1. **Wait.** Tasks run one at a time, so an upgrade never cuts off a running scan.
2. **Download and verify** to `/opt/scanning-node/releases/<version>/node.jar`. Checksum mismatch:
   file deleted, task `FAILED`, nothing changes.
3. **Swap** the `current` symlink in one atomic rename, so it is never broken.
4. **Record** `upgrade.json = {from, to, phase: SWAPPED}`, queue `UPGRADE_STARTED` and the task
   result (all on disk).
5. **Exit 0.** systemd (`Restart=always`) starts the new version.
6. **Confirm.** After its first successful heartbeat, the new version sees `SWAPPED` to its own
   version, emits `UPGRADE_COMPLETED` and deletes `upgrade.json`. "Completed" therefore means it
   runs and reaches the manager.

**Rollback.** systemd starts the node through `deploy/node-start-with-rollback.sh`. While
`upgrade.json` is `SWAPPED`, it counts starts in `start-attempts`; after 3 failed starts it points
`current` back to `from` and sets the phase to `ROLLED_BACK`. The old version starts and emits
`UPGRADE_ROLLED_BACK` after its first heartbeat. It is a shell script because a release that
crashes on startup cannot roll itself back.

**Preserved.** Identity, config and the outbox live in `/var/lib/scanning-node` and
`/etc/scanning-node`, outside `releases/`, so the node keeps its ID and undelivered results. Old
releases stay on disk, which is what makes rollback possible.

**Demonstrated:** 1.0.0 → 1.1.0 on both demo nodes (both built from the same source), and rollback
from a deliberately broken release.

**Limitations:** a failed upgrade is a `FAILED` task, not an audit event; old releases are never
cleaned up; the checksum comes from the manager over TLS, so it protects against corrupt or
tampered downloads but not a compromised manager (production: signed releases).

### nmap

Same task with `component: nmap`: the dashboard offers each `.deb`/`.rpm` in `releases/`;
the node verifies it and runs `apt-get install -y <file>` / `dnf install -y <file>` (which resolve
dependencies), then reports the new version with `NMAP_UPGRADED`.

**Status: not demonstrated.** Ubuntu 22.04 ships nmap 7.80; Ubuntu 24.04's 7.94 package needs glibc
2.38 (22.04 has 2.35), and the nmap.org RPM is x86_64 only. Next step: build a newer nmap package
for 22.04 once and host it. The mechanism is unit-tested.

## 7. Failure recovery

| Failure | Guarantee | Mechanism |
|---|---|---|
| Manager down | Node keeps scanning | Disk outbox; backoff 10 s → 5 min with jitter |
| Result rejected for good (403/404) | Node never stuck | Logged and dropped; outbox flushes after the heartbeat |
| Node restart | Nothing lost | Every state change on disk before acknowledgement |
| Host reboot | Same identity | systemd `enabled`; `identity.json` persists |
| Scan interrupted | Explicit outcome | `RUNNING` on boot → `INTERRUPTED` → re-queued once |
| Retry / duplicate | No duplicates | Terminal task = duplicate result; unique `eventId` |
| Upgrade interrupted or broken | Old or new version, never neither | Checksum, atomic symlink, rollback launcher |
| Manager restart mid long-poll | Node retries | Long-poll is stateless on the server |

## 8. Audit history

| Event | Recorded when |
|---|---|
| `INSTALLED` / `REINSTALLED` | `install.sh` runs on a host without / with an existing identity |
| `STARTED` | the agent starts (install, reboot, restart, upgrade); detail has app and nmap versions |
| `UPGRADE_STARTED` | the old version has verified and swapped in the new JAR, just before exiting |
| `UPGRADE_COMPLETED` | the new version's first successful heartbeat, so "completed" means it runs and reaches the manager |
| `UPGRADE_ROLLED_BACK` | the old version is back after 3 failed starts of the new one |
| `NMAP_UPGRADED` | the nmap package installed; detail is the before → after version |

Each event is a file in the node's `outbox/` with a node-generated UUID and timestamp, written
before delivery, so events recorded offline sync on reconnect. The manager takes the node ID from
the authenticated request, skips known `eventId`s (unique in the database), and shows events per
node and on `/audit`. A failed upgrade is not an event: its task is `FAILED` and nothing changes.

## 9. Scaling to ~3,000 nodes

**Load:** a cycle is 10–40 s, so ~75 req/s idle, ≤ 300 req/s peak; a few hundred scans per hour.

- **Manager:** trivial load for one instance; ≤ 3,000 held long-polls are cheap (`DeferredResult`,
  no blocked threads). Stateless, so N instances behind a load balancer, no sticky sessions.
- **Dashboard:** tables paged (50 rows), counts are indexed database queries.
- **Database:** H2 → PostgreSQL (config change); index `last_seen`, `status`, `node_id`.
- **Storage:** releases on object storage / CDN; raw nmap output in blob storage.
- **Thundering herd:** randomised backoff (+0–25 %).
- **Signed releases:** the build signs each JAR and package with an offline key; nodes carry the
  public key and install only what verifies, so a compromised or impersonated manager cannot push
  code. Also refuse downgrades and roll out to a few nodes first.
- **Auth:** per-node mTLS with rotation and revocation; CA-signed manager cert; optional end-to-end
  payload encryption if TLS terminates at a proxy.
- **Node:** a static Go binary instead of the JVM; same protocol.

**Known weaknesses:** nodes install whatever the manager sends, as root, so whoever controls (or,
with the committed key, impersonates) the manager controls the fleet; one shared enrollment token (manual rotation); private TLS key committed (demo
only); node secret is a plaintext 0600 file; if the manager's database is reset, nodes get 401 until
`identity.json` is deleted; no rate limit on `/enroll`; IPv4 only; H2; no multi-tenancy; nmap
upgrade not demonstrated.

## 10. Testing

- **Manager:** `@WebMvcTest` for controllers (validation, auth, status codes, paging), JUnit +
  Mockito for services, one `@SpringBootTest` enroll → scan → result flow on in-memory H2.
- **Node:** outbox, XML parsing, upgrade and rollback reporting, interrupted tasks, delivery after
  a manager outage.
- **Live (`./demo.sh`):** install, scans, reinstall, reboot, app upgrade, manager killed mid-scan,
  node rebooted mid-scan, rollback from a broken release.

Deviations from the original plan: the service runs as root (nmap upgrades install OS packages);
targets are IPv4/CIDR only.

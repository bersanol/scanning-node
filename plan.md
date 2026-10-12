# plan.md — Remote Scanning Node

Architecture and design decisions for the Remote Scanning Node exercise: a cloud
manager, a web dashboard and Linux node agents that run real `nmap` scans and
report results. Designed for ~3,000 nodes; demonstrated with two.

This document is the design baseline. Sections marked **(status)** are updated as
implementation progresses.

---

## 1. Components

| Component | Technology | Where it runs |
|---|---|---|
| **Manager** | Spring Boot 3, Spring Data JPA, H2 (file-backed), Thymeleaf | Operator machine, HTTPS on port 8443 |
| **Node agent** | Spring Boot 3 (thin: scheduler + `HttpClient`), state in JSON files | Ubuntu / RHEL host, systemd service |
| **Dashboard** | Server-rendered Thymeleaf pages inside the manager | Browser |
| **Installer** | Single `install.sh` (bash) | Run as root on a clean host |
| **Common** | DTOs shared by manager and node | Maven module |

Maven multi-module build: `common`, `manager`, `node`. Compiled with `--release 21`
so the node runs on the OpenJDK 21 shipped by Ubuntu and RHEL repositories.

**Why Java for the node too:** one toolchain, one set of tests, one release
pipeline. Cost: the installer must install a JDK (~200 MB). Accepted for this
exercise; see §9 for the production alternative.

---

## 2. Communication: HTTPS long-poll, node-initiated only

**Constraint:** only nodes may open connections. The manager listens on a single
HTTPS port and never connects to a node (no SSH, no inbound control port).

### Node loop (every 15 s)

1. `POST /api/v1/nodes/{id}/heartbeat` — carries app version, nmap version,
   hostname, OS, and the IDs of tasks currently held. The response carries at
   most one **pending task** (scan or upgrade). If none is queued, the manager
   holds the request open for up to 30 s (long-poll), so dispatch latency is
   near-instant without a second endpoint.
2. Flush the local **outbox**: unsent scan results
   (`POST /api/v1/tasks/{taskId}/result`) and audit events
   (`POST /api/v1/nodes/{id}/events`).

### Decision and rejected alternatives

| Option | Verdict | Reason |
|---|---|---|
| **HTTPS long-poll** | **Chosen** | No broker, works through any egress proxy, stateless manager, idempotent retries are natural |
| WebSocket | Rejected | Stateful connections complicate horizontal scaling and load balancers; no real benefit at 15 s cadence |
| MQTT | Rejected | Extra broker to install, secure and operate; overkill for request/response tasks |
| Reverse SSH tunnel | Rejected | Effectively gives the manager a control channel into the node; violates the spirit of the constraint |

### Availability rule

A node is **online** if `lastSeen` is less than 45 s old (three missed
heartbeats). Computed at read time, never stored, so a manager restart does not
produce false "offline" states.

---

## 3. Authentication

Both directions are covered.

### Node → manager (each node authenticated separately)

- The manager generates an **enrollment token** (shown in the dashboard). The
  operator passes it to `install.sh`.
- On first start the node generates a UUID and calls `POST /api/v1/enroll` with
  the token. The manager returns a random **per-node secret** and stores only a
  SHA-256 hash of it. Enrolling an ID that already exists is rejected (409), so
  someone holding the shared token cannot re-enroll a known node ID and lock the
  real node out.
- Identity is written to `/var/lib/scanning-node/identity.json` (mode 0600).
- Every later request sends `Authorization: Bearer <nodeId>:<secret>`.
  Each handler calls `nodes.authenticate(...)` first; a node can only act on its
  own ID.
- Re-running the installer finds `identity.json` and skips enrollment, so the
  node keeps the same identity.

### Why the secret is stored on the node in plaintext

- The node runs unattended and must reconnect by itself after a reboot, crash or
  upgrade, so its credential has to be readable at startup without a human.
  Encrypting the file only moves the problem (the decryption key must be on disk
  too); an mTLS private key would be a file on disk as well. This is how SSH
  keys, kubelet certificates and cloud agents work.
- Protection is file ownership: `identity.json` is mode 0600, readable only by the
  service user/root. Anyone who can read it already owns the node (can run nmap,
  read process memory, replace the agent), so the file adds little to them.
- Blast radius: a stolen secret impersonates **that one node** only — not other
  nodes, not the manager. The manager stores only SHA-256 hashes, so its
  database is useless for logging in. (SHA-256 rather than bcrypt is fine: the
  secret is 256 random bits, not a guessable human password.)
- **Production:** I would most likely replace the bearer secret with **mTLS
  using per-node certificates** issued at enrollment, ideally with the private
  key generated in and bound to the host's TPM (non-exportable), so a copied file
  is useless. A cheaper intermediate step is systemd encrypted credentials
  (`LoadCredentialEncrypted=`, sealed to the TPM). Chosen against here for
  simplicity: per-node mTLS needs a CA, a CSR/signing flow, client-cert
  configuration on both sides and revocation, and still needs the enrollment
  token for first contact.

### Manager → node (node verifies the manager's identity)

- One self-signed key pair, created once by `deploy/generate-tls-keys.sh` (plain
  `keytool`) and committed: `manager/src/main/resources/tls/manager.p12` (key +
  cert) and `node/src/main/resources/tls/truststore.p12` (that cert only).
- Both ship inside the JARs as Spring SSL bundles: the manager serves with
  `server.ssl.bundle=manager`; the node builds its stock `HttpClient` from the
  `manager` bundle's `SSLContext`. Nothing is generated or imported at deploy or
  install time, and no custom TLS code exists on either side: the JDK's normal
  certificate and hostname verification proves the manager's identity on every
  connection. An impostor manager fails the handshake.
- The SAN covers `localhost`, `manager`, `host.docker.internal`, `127.0.0.1`.
  Another host/IP or a new cert means rerunning the script, rebuilding, and
  rolling a node release (the app upgrade path).
- **Why self-signed:** chosen purely for simplicity in this demo. In production the
  manager would serve a certificate signed by a CA (public or internal), so nodes
  validate it through the normal CA chain and the cert can be rotated or the
  manager moved without shipping a new node release.

### Rejected alternatives

- **Generate the cert per deployment and import it at install time:** more moving
  parts on every deploy (keygen, fetch, `keytool -import`, JVM flags) for no gain in
  a demo; replaced by one committed pair packaged in the JARs.
- **Plain HTTP + HMAC-signed responses:** cheaper but leaves traffic in clear and
  only authenticates the body, not the channel.
- **mTLS with per-node client certs:** the "right" answer at scale but adds a CA
  and issuance flow that is not justified in the time available. See §9.

---

## 4. Scans

- Dashboard form: target (IP/CIDR) + target node → `ScanTask` in state `QUEUED`.
- The node receives the task in a heartbeat response and writes
  `tasks/<taskId>.json` **before** acknowledging it in the next heartbeat.
- It runs `nmap -oX - <target>` via `ProcessBuilder` with a hard timeout
  (default 10 min). The target is validated as an IP or CIDR; it is never passed
  through a shell.
- Result = exit code, raw stdout/stderr, and structured hosts/ports/state/service
  parsed from the XML. It is written to the outbox first, then POSTed.
- The manager persists `ScanResult` with a **unique constraint on `taskId`**. A
  repeated POST for the same task returns 200 and changes nothing.

### Task state machine

```
QUEUED → ASSIGNED → RUNNING → DONE
                           → FAILED        (nmap non-zero exit, timeout, bad target)
                           → INTERRUPTED   (node crashed / upgraded mid-scan)
```

`INTERRUPTED` tasks are re-queued once by the manager, then marked `FAILED`.
Every task reaches an explicit terminal state.

---

## 5. Dashboard

| Page | Shows |
|---|---|
| `/` | Registered count, online count, node table: ID, hostname, OS, status, last seen, app version, nmap version |
| `/nodes/{id}` | Node detail, scan history, audit events, upgrade actions |
| `/scans`, `/scans/{taskId}` | Task status, structured results, raw output |
| `/scans/new` | Submit a scan to a chosen node |
| `/audit` | All install/upgrade events across nodes |

Plain HTML forms, no JavaScript framework. Functional UI is sufficient per the
brief.

---

## 6. Remote upgrades

### Application

The manager serves every file in `releases/` at `/releases/`. The dashboard lists
each `node-<version>.jar` there as an upgrade option and computes its SHA-256; an
upgrade task carries the URL, target version and checksum. The node:

1. Runs tasks one at a time on a single worker, so an upgrade starts only after
   the scan in progress has finished; no scan is cut off by an upgrade.
2. Downloads the JAR to `releases/<X>/node.jar` and verifies the SHA-256
   (mismatch → task `FAILED`, nothing changes).
3. Atomically repoints the `current` symlink, writes
   `upgrade.json = {from, to, phase: SWAPPED}` and queues an `UPGRADE_STARTED` event.
4. Queues the task result in the outbox, then exits with code 0; systemd
   (`Restart=always`) starts the new version, which delivers the queued result.
5. After its first successful heartbeat, the new version emits
   `UPGRADE_COMPLETED` (before → after versions) and deletes `upgrade.json` and
   the start counter.
6. **Rollback:** the systemd unit runs `deploy/node-start-with-rollback.sh`. While
   `upgrade.json` is `SWAPPED` it counts starts in `start-attempts`; after 3 failed
   starts it repoints `current` to `from` and sets the phase to `ROLLED_BACK`. The
   old version then emits `UPGRADE_ROLLED_BACK` after its first heartbeat and
   deletes `upgrade.json`. Recovery is autonomous.

Identity, configuration and the outbox live outside `releases/`, so upgrades
never touch them. Two releases are shipped: `1.0.0` and `1.1.0`.

**Scripts for this requirement (6):**
- `deploy/node-start-with-rollback.sh`: the node launcher (step 6). It must be a
  shell script, because a broken release never starts and so cannot roll itself back.
- `Dockerfile` (`releases` target) / `demo.sh local`: build `1.0.0` and `1.1.0`
  from the same source (`-Dnode.version`) and stage them in `releases/`, where
  the manager serves them.

### nmap

Same task type with `component: nmap`. The dashboard lists every `.deb`/`.rpm`
in `releases/` as an nmap upgrade. The node downloads the package, verifies the
SHA-256, and installs the local file with `apt-get install -y` (Ubuntu) or
`dnf install -y` (RHEL). Unlike `dpkg -i` / `rpm -U`, these also pull any missing
dependencies from the distro repositories. It then re-reads `nmap --version` and
reports it in the next heartbeat with an `NMAP_UPGRADED` audit event.

**(status)** Demo path: Ubuntu 22.04 ships nmap 7.80. Ubuntu 24.04's 7.94 package
cannot be used: it needs glibc 2.38 (22.04 has 2.35) and a matching
`nmap-common`. The next step is to package a newer nmap
for 22.04 once (e.g. built from the nmap.org source) and host it in `releases/`;
the node installs it with `apt-get` like any package. A first attempt hit build
tooling issues and was dropped for time. **Not demonstrated end to end yet**; the
mechanism is unit-tested. Known limitation: the official nmap.org RPM is x86_64 only, so the RHEL
nmap upgrade is not demonstrated on an arm64 development machine. The mechanism
is identical.

---

## 7. Failure recovery guarantees

| Failure | Guarantee | Mechanism |
|---|---|---|
| Manager down | Node keeps scanning; results queue locally | Disk outbox; backoff 15 s → 5 min with jitter |
| Node process restart | Nothing lost | Every state transition written to disk before acknowledgement |
| Host reboot | Service returns with the same identity | systemd `enabled`; `identity.json` persists |
| Scan interrupted mid-run | Explicit outcome | On boot, any locally `RUNNING` task is reported `INTERRUPTED`; manager re-queues once, then `FAILED` |
| Duplicate delivery / retry | No duplicate results, no inconsistent state | Unique `taskId` on results, unique `eventId` on audit events; repeats are idempotent 200s |
| Upgrade interrupted (power loss mid-download, bad JAR) | Converges to old or new version, never neither | Download to temp + checksum; symlink swap is atomic; `upgrade.json` + rollback wrapper |
| Manager restarted mid long-poll | Node simply retries | Long-poll is stateless on the server |

---

## 8. Audit history

Events (`INSTALLED`/`REINSTALLED` written by `install.sh`, then `STARTED`,
`UPGRADE_STARTED`, `UPGRADE_COMPLETED`, `UPGRADE_ROLLED_BACK`, `NMAP_UPGRADED`)
are written as one file each in the node's `outbox/`, each with a UUID and
timestamp. Events are flushed through the outbox, so those recorded
offline sync on reconnection. The manager deduplicates on `eventId` and shows
events per node and globally.

---

## 9. Scaling to ~3,000 nodes

**Workload assumptions:** 3,000 nodes, 15 s heartbeat → 200 requests/s. A few
hundred scans per hour; raw nmap output ≤ a few MB each.

- **Manager:** 200 req/s is trivial for one Spring Boot instance. Long-poll
  holds ≤ 3,000 open connections; with Java 21 virtual threads this is cheap.
  Horizontal scaling: the manager is stateless, so run N instances behind any
  HTTP load balancer. Long-poll needs no sticky sessions.
- **Database:** swap H2 for PostgreSQL — a configuration change with JPA. Index
  `node.last_seen`, `task.status`, `task.node_id`.
- **Release artifacts:** move to object storage / CDN; nodes still only pull.
- **Thundering herd:** heartbeats are jittered ±20 % and backoff is randomised,
  so a manager restart does not cause a synchronised storm.
- **Raw output:** move from DB columns to blob storage with a reference.
- **Authentication:** move to mTLS with per-node certificates issued at
  enrollment, giving a revocation path and rotation. The manager's own cert is
  CA-signed instead of the committed self-signed pair.
- **Defence in depth:** for extra security, combine mTLS with payload-level
  encryption (e.g. encrypt/sign task and result bodies end to end), so data stays
  protected even if TLS is terminated at a proxy or load balancer, or a channel
  is compromised.
- **Node runtime:** for a fleet, replace the JVM agent with a static binary
  (Go) to cut installer size and memory; the protocol is unchanged.

### Known weaknesses (honest list)

- Single shared enrollment token; rotation is manual via the dashboard.
- One committed TLS key pair (private key in the repo): fine for a demo, never for
  production (would use a CA-signed cert). Rotation means a new node release.
- Node secret is a plaintext file on the node (0600); anyone with root on that
  host can impersonate it. Production: per-node mTLS with a TPM-bound key.
- No rate limiting on `/enroll`.
- H2 is a demo database, not a production one.
- The nmap upgrade depends on a distro-compatible package being hosted; it is
  not yet demonstrated end to end (no compatible newer package for Ubuntu 22.04
  on arm64 yet).
- No per-tenant separation; one manager, one flat fleet.

---

## 10. Testing strategy

The manager follows classic Spring MVC layering (controller → service →
repository), which is what makes it unit-testable at each level.

- **Manager:** `@WebMvcTest` + `MockMvc` for controllers (request validation,
  authentication, status codes); plain JUnit 5 + Mockito for services (enrollment,
  task assignment, idempotent result handling, availability rule);
  `@DataJpaTest` on H2 for repositories (unique constraints on `taskId` /
  `eventId`, queries).
- **Node:** unit tests for outbox persistence, nmap XML parsing, upgrade state
  machine, interrupted-task detection on boot.
- **End-to-end (manual, README):** `deploy/docker/compose.yml` starts two clean
  hosts (Ubuntu 22.04, Rocky 9 as RHEL-compatible) with systemd as PID 1;
  `install.sh` is run in each, then scans, reinstall, reboot and both upgrades
  are checked from the dashboard.

---

## 11. Demo script

1. `./demo.sh` (needs only Docker): builds from source in Docker, starts the
   manager container and two clean hosts, runs `install.sh` in each; both nodes
   come online. `./demo.sh local` runs the manager with a local JDK instead.
   TLS needs no setup: the cert ships in the JARs.
2. Open the dashboard (`https://localhost:8443/`).
3. Submit a scan to each node concurrently; results and raw nmap output appear.
4. Stop the manager while a scan runs; restart it; the result arrives once.
5. Trigger app upgrade 1.0.0 → 1.1.0 on both nodes; versions and audit events
   update.
6. nmap upgrade on the Ubuntu node: 7.80 → 7.95 (pending a working package, §6).
7. `docker restart` a node (a reboot); systemd starts it and it returns with the
   same ID. Rerun `install.sh`: `REINSTALLED`, same ID.

---

## 12. Implementation roadmap

Each part ends with a check that can be run before moving on.

### Part 1 — Manager core (API, persistence, auth, TLS)

Deliverables:
- Root `pom.xml` (multi-module, `--release 21`, Spring Boot parent) and
  `common/`, `manager/`, `node/` module skeletons.
- `common`: DTOs — `EnrollRequest/Response`, `HeartbeatRequest/Response`,
  `TaskDto` (scan or upgrade), `ScanResultDto`, `AuditEventDto`.
- `manager` entities + Spring Data repositories: `Node` (id, secretHash,
  hostname, os, appVersion, nmapVersion, lastSeen, enrolledAt), `ScanTask`
  (id, nodeId, target, status, timestamps, retryCount), `ScanResult`
  (taskId unique, exitCode, rawOutput, structured JSON), `AuditEvent`
  (eventId unique, nodeId, type, detail, occurredAt), `UpgradeTask`
  (id, nodeId, component, targetVersion, status).
- Endpoints: `POST /api/v1/enroll`, `POST /api/v1/nodes/{id}/heartbeat`
  (long-poll ≤ 30 s), `POST /api/v1/tasks/{taskId}/result` (idempotent),
  `POST /api/v1/nodes/{id}/events` (idempotent batch).
- `NodeService.authenticate` (called first in each handler): validates
  `Bearer <nodeId>:<secret>` against the stored hash; a node may only act on its
  own ID. `/enroll` validated by enrollment
  token instead.
- TLS: standard `server.ssl` with the committed keystore packaged in the JAR.
- `application.yml`: file-backed H2, port 8443, enrollment token from env or
  generated and logged.

Done-check: `mvn test` passes with tests for enroll, rejected bad token,
heartbeat assigns a queued task, duplicate result POST is a no-op.
`curl -k https://localhost:8443/api/v1/enroll` works end to end.

**(status) Done, then deliberately simplified.** A first pass had 30 classes;
it was cut to 10 manager classes + 1 protocol file so every piece is explainable
in a sentence:

- `Protocol.java` — the whole wire format (records + enums) in one file.
- `Node`, `Task`, `AuditEvent` — three entities. Scan results live on `Task`;
  idempotency is simply "a result for a terminal task is a duplicate".
- `NodeService` — enrollment, bearer authentication, liveness, audit events.
- `TaskService` — queue, long-poll (`DeferredResult` map), acknowledgement,
  results, interrupted-retry.
- `NodeApiController` — the four endpoints; auth is an explicit first line.
- `ManagerApplication` — boot + `/releases/` static files.
- Errors are Spring's `ResponseStatusException`; config is two `@Value`s.

20 tests green (service, controller, full-flow on H2). Verified live over HTTPS:
bad token → 401, enroll → secret, no credentials → 401, another node's path →
403, event replay → `duplicates:1`, unknown task → 404, heartbeat held open.
Deviation from §4: IPv4/CIDR targets only (IPv6 is a known limitation).

### Part 2 — Node agent

Deliverables:
- `NodeState` on disk: `identity.json` (0600), `tasks/<taskId>.json`,
  `outbox/` (one file per result/event), `upgrade.json`. Config comes from
  `/etc/scanning-node/node.env`, written by `install.sh`.
- `ManagerClient`: stock `HttpClient` on the packaged `manager` SSL bundle;
  enroll, heartbeat, postResult, postEvents; backoff 15 s → 5 min with jitter.
- `Agent`: scheduled loop — heartbeat, accept task (write to disk before
  next heartbeat acknowledges), flush outbox.
- `Nmap`: validates target as IP/CIDR, runs `nmap -oX -` via
  `ProcessBuilder` with timeout, parses XML into structured result, writes to
  outbox.
- Upgrades (in `Agent`): download → verify SHA-256 → symlink swap → write
  `upgrade.json` → exit 0. nmap variant: download package → `apt-get install` /
  `dnf install` of the local file → re-read version.
- Boot recovery: any local task in `RUNNING` → report `INTERRUPTED`; if
  `upgrade.json` is `SWAPPED` → emit `UPGRADE_COMPLETED` after the first
  heartbeat and delete `upgrade.json`.
- `node-start-with-rollback.sh` wrapper: start counter + rollback of `current` symlink after 3
  failed starts.
- Version `1.0.0`; `1.1.0` differs by version string and a changelog line.

Done-check: node runs locally against the manager (`java -jar node.jar`
pointing at `/tmp` state dir), appears online, completes a scan of
`127.0.0.1`, result visible via API. Kill manager mid-scan, restart, result
arrives once. Unit tests for parser, outbox, upgrade state machine.

### Part 3 — Dashboard, installer, deployment

Deliverables:
- Thymeleaf pages: `/`, `/nodes/{id}`, `/scans`, `/scans/{taskId}`,
  `/scans/new`, `/audit`; upgrade and scan actions as POST forms.
- `/releases/node-<v>.jar` and nmap `.deb`/`.rpm` packages served from a configurable
  directory (no manifest; the dashboard lists the folder).
- `deploy/install.sh`: detect Ubuntu/RHEL via `/etc/os-release`; `apt`/`dnf`
  install OpenJDK 21 + nmap; create `/var/lib/scanning-node` (0700) and
  `/opt/scanning-node`; install the JAR from the installer bundle (it already
  trusts the manager cert, so nothing is fetched with unverified TLS); write
  `/etc/scanning-node/node.env` (0600); install systemd unit
  (`Restart=always`, `enabled`); keep identity and `current` if present; queue an
  `INSTALLED`/`REINSTALLED` event. Validated: Ubuntu 22.04, Rocky 9; Ubuntu
  24.04 and RHEL 8 warn as not validated.
- `deploy/docker/`: `Dockerfile.ubuntu` (22.04), `Dockerfile.rocky` (Rocky 9),
  base image plus systemd as PID 1 so `install.sh` runs as on a VM;
  `compose.yml` with the manager (port 8443) and two hosts (no published ports);
  nodes reach the manager as `manager` or, with a local manager,
  `host.docker.internal`, both in the certificate's SAN.
- `Dockerfile` (root): builds all modules with Maven + JDK 21 and produces the
  manager image, plus a `releases` target exporting the node JARs, so a
  reviewer needs only Docker.
- `demo.sh`: one command for the whole demo (`docker` / `local` / `down`);
  offers to install Docker if missing (get.docker.com on Linux, Homebrew on
  macOS, `winget` instructions on Windows/WSL2).

**(status)** Done. Deviations: the service runs as root (nmap upgrades install
OS packages, so a dedicated user would need sudo rules); `demo.sh` replaces the
planned smoke script, and the README lists the manual checks. Verified: a clean
`./demo.sh` builds, installs both nodes (2 registered, 2 online) and a scan
reaches `DONE`.

Done-check: full demo script (§11) runs end to end.

### Part 4 — Documentation and hand-in

- `README.md`: prerequisites, exact build/run commands, expected outcomes,
  supported OS versions, how availability is determined.
- `AI_USAGE.md`: tools and models, workflow, representative prompts, how
  output was validated and corrected (kept as a running log from Part 1).
- `plan.md`: update **(status)** sections with what was actually built and
  any deviations.

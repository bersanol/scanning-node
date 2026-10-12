
# AI_USAGE.md

How AI was used to build this project, kept as a running log during development.

## Tools and models

- **Claude Code** (Anthropic CLI) running **Claude Fable 5.1**, and **Claude Opus 5.5** for the
  authentication review, installer and Docker deployment work; used for all implementation work:
  design, code, tests, deployment assets and documentation.
- The engineer directed the work, reviewed every file, ran the build/tests locally and made
  the architecture and scope decisions recorded in `plan.md`.

## Workflow

1. **Design first.** The requirements were pasted in full and the model proposed an
   architecture. The engineer asked for a detailed plan before any code was written; the
   result was `plan.md`, including an implementation roadmap with a done-check per part.
2. **Guard rails in `CLAUDE.md`.** Project invariants were written down so every later
   generation step honours them: node-initiated connections only, idempotent writes keyed by
   `taskId`/`eventId`, disk-before-ack on the node, no shell for scan targets, Spring MVC
   layering, tests at every layer.
3. **Part-by-part implementation.** Each part of the roadmap was generated as a batch of
   files, compiled, and tested before moving on.
4. **Validation loop.** Build and test output was fed back; failures were diagnosed and fixed
   with the reasoning recorded below.

## Representative prompts

- "this will be spring boot application, we should also use spring data and h2 for database"
- "tell me the plan in detail" — produced the architecture, rejected alternatives, recovery
  guarantees and 3,000-node scaling plan.
- "will you also provide unit tests? I want mvc approach" — pinned the controller → service →
  repository layering and the `@WebMvcTest` / Mockito / `@DataJpaTest` split.
- "implement part 1" — manager core: entities, repositories, services, API controllers, auth
  filter, TLS bootstrap, 57 tests.

- "how does the node approve the manager? this is self signed certificate" → "what is this
  Authorization: Bearer <nodeId>:<secret>? do we use this or need it?" → "we save password to
  files? looks very suspicious" — a walk-through of both authentication directions that ended in
  two security fixes and a documented production path (CA-signed manager cert, per-node mTLS).
- "let's talk how we deploy it, I have docker installed" — led to systemd-in-Docker clean hosts
  (Ubuntu 22.04, Rocky 9) and the installer-bundle approach.
- "the readme is way too long, people need to read it for the interview" → "they might not have
  a mac, or java or docker ... add a script that does it for them" — README cut from 206 to ~100
  lines; added a per-OS prerequisites table, a manager `Dockerfile` that builds from source, and
  `demo.sh`, which installs Docker if needed and runs the whole demo.

## Validation and corrections

| What was generated | How it was validated | Correction |
|---|---|---|
| Root `pom.xml` | `mvn compile` | A comment contained `--release`, which is illegal inside an XML comment; reworded. |
| `RepositoryConstraintsTest` | `mvn test` | Hibernate surfaced the second unique-key violation as an internal `AssertionFailure` rather than `DataIntegrityViolationException`; the test now asserts the row is rejected regardless of wrapper type. The constraint itself was confirmed by the logged `Unique index or primary key violation`. |
| First Part 1 implementation (30 classes) | Engineer review: "complex and hard to explain" | Rewritten to 10 manager classes + one `Protocol.java`: filter, custom exceptions, properties class, dispatcher/heartbeat services, TLS classes and the `ScanResult` entity were all folded away. Same 4 endpoints, same guarantees, 20 tests. |
| Custom `TrustManager` + in-process `keytool` | Engineer review: "why do we need all this tls and trust store configuration?" | Replaced by standard TLS: the installer imports the manager cert into a JVM truststore; node uses `HttpClient.newHttpClient()`, manager uses plain `server.ssl.*`. ~80 lines of security-sensitive code removed. |
| Per-deployment keygen + installer truststore import | Engineer review: didn't want to recreate the trust store on every deploy | One key pair generated once and committed; manager keystore and node truststore ship inside the JARs as Spring SSL bundles. Installer and JVM truststore flags no longer touch TLS. |
| Lombok on entities | `mvn compile` | Lombok 1.18.46 generates nothing under javac 26 ("cannot find symbol" on every getter); removed, accessors hand-written. |
| Java target | Checked distro package availability | JDK 26 was the IDE default; nodes must run on the distro's OpenJDK 21, so the build targets `--release 21`. |
| Spring Boot version | Queried Maven Central metadata | Pinned to 3.5.16 instead of guessing. |
| `NodeService.enroll` | Engineer questioned the auth design; model re-read the code | Re-enrolling an existing `nodeId` silently rotated its secret, so anyone with the shared token could take over a node. Now 409; the test that asserted the old behaviour was replaced. |
| `identity.json` | Same review; `plan.md` claimed mode 0600 | The code never set it (umask, usually 0644). Now created 0600 before any byte is written; new test checks the permissions. |
| `plan.md` auth text | Compared with the code | Still described a servlet filter (`NodeAuthFilter`) that had been removed; corrected to the explicit `nodes.authenticate(...)` call. |
| `deploy/install.sh` | Ran on clean Ubuntu 22.04 and Rocky 9 containers with systemd | Sourcing `/etc/os-release` overwrote the script's `VERSION` variable, so the app was installed under `releases/22.04.5 LTS (Jammy Jellyfish)/`. Now reads only the needed fields. Then verified: enroll, scan, reinstall keeps ID, reboot, app upgrade 1.0.0 → 1.1.0. |
| Dashboard install hint | Review during deployment | Suggested `curl -k ... install.sh \| sudo bash`, i.e. an unverified download run as root; replaced with the installer-bundle command. |
| nmap upgrade demo | `apt-cache show nmap` on 24.04 | The plan's 24.04 nmap 7.94 `.deb` needs glibc 2.38; 22.04 has 2.35. A source-built 7.95 `.deb` was attempted and failed twice on build tooling; the engineer stopped it for time. Documented as not yet demonstrated. |
| `Dockerfile` + `demo.sh` | Clean `./demo.sh` run with only Docker, then `./demo.sh down` | Passed first time: JDK 21 build in Docker, manager container up, both nodes installed via `https://manager:8443`, 2 online, scan `DONE`. `local` mode and the Docker auto-install path were not exercised. |

## Decisions the engineer made

- Java/Spring Boot for both manager and node (one toolchain) rather than a Go/Python agent.
- HTTPS long-poll over WebSocket/MQTT; one packaged TLS certificate over mTLS.
- Spring Data JPA with H2 for the exercise; PostgreSQL is a configuration change.

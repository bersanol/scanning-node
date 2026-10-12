# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Remote Scanning Node: a Spring Boot manager + dashboard, and a Spring Boot node agent that runs real `nmap` scans on Ubuntu/RHEL hosts. Design, decisions and guarantees are in `@plan.md` — read it before changing protocol, auth, upgrade or recovery behaviour.

Maven multi-module: `common` (the whole wire protocol in one file, `Protocol.java`), `manager` (Spring Data JPA + H2 + Thymeleaf), `node` (agent, state in JSON files under `/var/lib/scanning-node`).

This is an interview project: keep it concise. Prefer fewer classes with one clear job each over extra layers; don't add a class, property, or abstraction unless a requirement needs it. No Lombok (it doesn't work on the local javac 26).

## Build

- Build with the local JDK 26, but compile with `--release 21`: nodes run on the distro's OpenJDK 21. Do not use APIs newer than Java 21.
- No Maven wrapper; use the system `mvn`.
- Build: `mvn package`; test: `mvn test`; single test: `mvn -pl manager test -Dtest=ClassName#method`.

## Conventions

- Spring MVC (servlet stack), not WebFlux. Layering: `controller` (thin; auth is an explicit `nodes.authenticate(...)` first line, no filter) → `service` (all rules; two services: `NodeService` for identity/liveness/audit, `TaskService` for the task queue) → `repository`. Errors are `ResponseStatusException`, no custom exceptions or handlers. Config via `@Value`, no properties class.
- Long-poll endpoints return `DeferredResult`; do not block request threads.
- Idempotency rule: a result for a task that is already terminal is a duplicate; an audit event with a known `eventId` is a duplicate.
- Every part ships with tests: `@WebMvcTest` for the controller, plain JUnit/Mockito for services, one `@SpringBootTest` flow on in-memory H2. JUnit 5 (Jupiter); tests go in `src/test/java`.
- Only nodes initiate connections. Never add a manager→node call or an inbound port on the node.
- TLS is one committed key pair packaged in the JARs as Spring SSL bundles: manager `tls/manager.p12` (`server.ssl.bundle=manager`), node `tls/truststore.p12` (stock `HttpClient` built from the bundle's `SSLContext`). Never generate or import certs at deploy/install time; regenerate only via `deploy/generate-tls-keys.sh`. No custom TrustManagers or in-process keytool.
- All node→manager writes must be idempotent (keyed by `taskId` / `eventId`); retries must never create duplicates.
- Node state transitions are written to disk before being acknowledged to the manager.
- Never pass scan targets through a shell; validate as IP/CIDR and use `ProcessBuilder` args.

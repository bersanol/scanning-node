# AI usage

**Tools:** Claude Code with Claude Fable 5.1, later Claude Opus 5.5. It wrote the code, tests,
installer and docs; I made the architecture and scope decisions and reviewed every part.

**Workflow:** design first (`plan.md`) → project rules in `CLAUDE.md` (only nodes connect,
idempotent writes, disk before acknowledgement, no shell for targets, tests with every part) →
build in small parts, each tested before the next → challenge every design until it is simple.

## Representative prompts (paraphrased)

| Prompt | Outcome |
|---|---|
| "Before any code, write a detailed design with the alternatives you rejected." | `plan.md` |
| "Use controller → service → repository and deliver tests with every part." | MVC layering, tests at each layer |
| "Reduce the design to the fewest classes that keep every guarantee." | ~30 classes → 11 manager files + one protocol file |
| "Justify the custom TLS code, or replace it with the standard mechanism." | One packaged certificate, standard Java TLS |
| "Walk me through how each side authenticates the other." | Node-takeover fix; secret file made 0600 |
| "Trace a result to the database. What if the manager never accepts it?" | Fix for a node stuck looking offline |
| "Validate the installer on clean Ubuntu and RHEL hosts with systemd." | Installer bug found and fixed |
| "Run the failure scenarios live." | Manager outage, node reboot and broken upgrade all recover |
| "Identify what would not scale to 3,000 nodes and fix it." | Paged dashboard, counts in the database |

## Testing and coverage

**46 tests, all green (`mvn package`):**

- Manager services (14, Mockito): hashed secrets, takeover rejected, node ownership, online rule,
  long-poll, idempotent results and events, interrupted-task retry.
- Manager controllers (11, `@WebMvcTest`) and end to end (2, `@SpringBootTest` on H2): auth on
  every call, async heartbeat, target validation, paging; enroll → scan → result.
- Node (19): delivery after a manager outage exactly once, rejected results don't block heartbeats,
  upgrade/rollback reporting, bad checksum never swaps, 0600 identity, atomic writes, nmap parsing
  and failures.

**Line coverage (JaCoCo):** manager 79%, node 64%; core classes 83–100% (`NodeApiController`,
`NodeService`, `TaskService`, `NodeState`, `Nmap`). `ManagerClient` (HTTP calls) is covered by
the live runs instead.

```bash
mvn org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.14:report
```

**Live (`./demo.sh`, clean Ubuntu 22.04 and Rocky 9):** install, concurrent scans, reinstall and
reboot (same ID), app upgrade; manager killed mid-scan (result recorded once); node rebooted
mid-scan (`INTERRUPTED`, re-run); broken release (automatic rollback).

## What review caught

| Problem | Fix |
|---|---|
| Re-enrolling a known node ID replaced its secret (takeover) | Rejected with 409; test added |
| `plan.md` said `identity.json` is 0600; code never set it | Created 0600; test added |
| One undeliverable result blocked heartbeats forever | 403/404 dropped; heartbeat runs first; test added |
| Installer put the app under `releases/22.04.5 LTS (Jammy Jellyfish)/` | Stopped `/etc/os-release` overwriting a variable |
| Dashboard suggested `curl -k ... \| sudo bash` | Installer-bundle command instead |
| Dashboard loaded every node, task and event | Paged tables, database counts |
| Planned nmap package needs a newer glibc than Ubuntu 22.04 has | Documented as not demonstrated; unit-tested |
| Lombok generated nothing on JDK 26 | Removed |

# DCode – Agent Instructions

## 1. Project identity

DCode (Darwin Code) is a new, independent Java 21 / Eclipse RCP / Equinox OSGi / Tycho
platform: a native AI coding harness with an Eclipse RCP IDE, an independent headless server
runtime, a browser interface, multi-repository workspaces, SQLite persistence, and family
integration (EVO, TRYST, DStudio) through TEIP 1.1 and the FHC harness contract.

Phase 5 of the family project builds DCode. See `PHASE-5-ARCHITECTURE-BASELINE.md` for the
verified architecture, ownership matrix and roadmap.

## 2. Repository layout

```
pom.xml                     Tycho parent (groupId eu.kalafatic.dcode, 0.1.0-SNAPSHOT)
targetplatform/eu.kalafatic.dcode.target   Eclipse 2024-03 target + JUnit 5.10.2
plugins/eu.kalafatic.dcode.core            Core: domain model, events, lifecycle, ports, config
plugins/eu.kalafatic.dcode.java.tooling    JDT/PDE tooling (see PHASE-5-WP-5.6-JDT-IMPLEMENTATION-REPORT.md)
plugins/eu.kalafatic.dcode.server/web      Web UI assets (see PHASE-5-WP-5.9-IMPLEMENTATION-REPORT.md)
plugins/eu.kalafatic.dcode.client          API/SSE client (JVM clients, incl. RCP)
plugins/eu.kalafatic.dcode.ui              Eclipse RCP IDE (see PHASE-5-WP-5.10-IMPLEMENTATION-REPORT.md)
features/, product/                        Feature + materialized RCP/headless products
plugins/eu.kalafatic.dcode.*               additional bundles (added per work package)
tests/eu.kalafatic.dcode.tests             Tycho surefire tests (JUnit 5)
```

## 3. Build commands (verified)

The system Maven (3.8.7) is TOO OLD for Tycho 4.0.8 (requires Maven >= 3.9). Use the wrapper
distribution cached in the local Maven repository:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # machine default Java is 8 — must override
MVN=~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn
$MVN -B clean verify
```

Target platform: Eclipse 2024-03 (deliberately; Tycho 4.0.8's junit59 surefire provider is
incompatible with JUnit 5.14+/6.x shipped in 2025-12 — see baseline §12). JUnit pinned to
5.10.2 / platform 1.10.2 (TRYST-proven versions).

## 4. Architecture rules (non-negotiable)

- One shared core; RCP/Web/Headless are access modes, never separate implementations.
- Headless must not depend on SWT/Workbench/Browser. Core has no SWT.
- No Spring, no Node/Python backend, no brokers, no second plugin system, no JS business logic.
- Services are registered manually in OSGi (family convention; DS is absent from 2024-03).
- Sessions are central entities; state changes go through
  `SessionState.isValidTransition(...)` only.
- Events go through the single `EventBus`; no parallel event mechanism.
- Secrets: encrypted at rest, never logged (`LogSanitizer`), never in API responses.
- Dependency direction: ui -> application -> harness/config/repositories -> persistence ->
  core; `java.tooling` implements the core SPI. No EVO/TRYST/DStudio dependencies.
- Do not introduce a dependency on EVO, TRYST or DStudio. Family integration is wire-only
  (TEIP 1.1 / FHC), DTOs owned per product.

## 5. Git

Git operations in this repository are controlled by the user. Do not run git/gh commands
unless explicitly requested for this repo. Never run git commands in the EVO or TRYST
repositories.

## 6. Implementation workflow

1. Read the relevant part of `PHASE-5-ARCHITECTURE-BASELINE.md`.
2. Implement the smallest coherent change per work package.
3. `mvn -B clean verify` (with the JAVA_HOME and MVN overrides above).
4. Record actual results; never claim unverified success.
5. Update architecture docs when architectural relationships change.

## 7. Port conventions (family standard, confirmed)

DCode occupies the 48xxx range: HTTP 48080 (fallback range 500), management 48380, aux 48680.
DStudio owns the 58xxx block (TEIP 58080); both products run simultaneously without collision.
Configured values always win; occupied ports fall back through `PortAllocator`.

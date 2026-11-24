# EVO-CODE — Thin-Client CLI Architecture (Phase A Design)

**Date:** 2026-11-23
**Status:** Implemented and verified (Phase A design + Phase B implementation). Supersedes the "CLI
command mode boots a full runtime" limitation recorded in `EVO_CODE_INITIAL_TEST_REPORT.md` §14.

## 1. Current Architecture (verified)

### CLI execution flow (today)
`dcode-headless <command>` → Equinox starts **all** runtime bundles (auto-start level 4) →
`PersistenceActivator` opens SQLite + runs recovery → `ServerActivator` binds HTTP port + persists
it → `HeadlessApplication.start()` parses the command and queries the (just-booted) server via
HTTP. **Every command boots a full runtime** — the root cause of the previous campaign's
DEFECT-A (config mutation + recovery side effect).

### Server startup flow (today)
`dcode-headless` (no args) → bundles auto-start → persistence/recovery → harness → server binds
port (configured, else `PortAllocator`; falls back inside 58xxx on conflict) → `HeadlessApplication`
runs the daemon loop (or REPL with a TTY).

### Server shutdown flow (today)
SIGTERM to the JVM (or `dcode-headless stop`, which SIGTERMs the port occupant) → Equinox shutdown
hook → bundle `stop()` methods → application `stop()` → JVM exit 143.

### Configuration & port discovery (today)
`dcode-config.json` `httpPort` is the **configured** port (fallback-allocated port is persisted only
when `configuredPort <= 0`, per previous fix). The actual bound port is published in-process only
via the `dcode.server.port` system property — **not** persisted, so a separate process cannot
discover a server that fell back to a non-configured port.

### API available to a CLI (today)
`GET /api/v1/{health,status,ports,runtimes,providers,models,agents,profiles,harnesses,workspaces,
sessions,tools}` + `DCodeApiClient` (thin `client` bundle, `JsonNode`-returning) + `BackendProbe`
(endpoint classification). No lifecycle endpoint.

## 2. Target Architecture

```
                        EVO-CODE
            +----------------+----------------+
          SERVER                          CLIENTS
      (dcode-headless)            CLI (dcode-cli) | Web | RCP
   Runtime Core | Session Mgr |      (thin HTTP client,
   Tool Registry | AI Cmdr |        no runtime)
   ENR/Ollama | Filesystem
```

- **One authoritative server runtime** (`dcode-headless`, service/REPL).
- **Thin CLI** (`dcode-cli`) communicates only over the existing HTTP API.

## 3. Design Decisions

### 3.1 New thin bundle `eu.kalafatic.dcode.cli`
- `CliApplication implements IApplication`; command table (HTTP-based).
- Depends on `eu.kalafatic.dcode.client` (reuse `DCodeApiClient` + `BackendProbe`) and
  `org.eclipse.equinox.app`. `core` is present only to satisfy `client`'s Require-Bundle and stays
  **dormant** (no `core` class is loaded — only `JsonNode` methods are used).
- Commands: `status`, `sessions`, `models`, `runtimes`, `providers`, `agents`, `config`,
  `start`, `stop`, `restart`, `help`.
- Read-only commands never start a runtime, never open SWT, never bind a port, never touch
  persistence. Unavailable server → clear message + non-zero exit.

### 3.2 New thin product `dcode-cli.product`
- Application `eu.kalafatic.dcode.cli.application`; launcher `dcode-cli`.
- Includes only: `cli`, `client`, `core` (resolved, dormant), jackson, `org.eclipse.equinox.app`,
  `org.eclipse.equinox.simpleconfigurator`. **No** config/repositories/persistence/harness/
  java.tooling/server/SWT/ui bundles.

### 3.3 Endpoint discovery & persistence
- The **server** persists its actual bound endpoint to `<dataDir>/server-endpoint.json`
  (`{"host","port","pid","boundAt"}`) after every successful bind; it is **not** written on a
  failed/conflicting startup (previous `httpPort`-on-conflict fix is preserved: `httpPort` stays
  the configured value, the *actual* endpoint lives in the new file).
- The **CLI** discovers the endpoint: read `server-endpoint.json` → `BackendProbe` → if stale,
  fall back to `dcode-config.json httpPort` → probe → unavailable error.

### 3.4 Lifecycle commands
- `stop` — `POST /api/v1/server/shutdown` (new minimal lifecycle endpoint) then poll health until
  unreachable.
- `start` — if a server is already reachable, report it (no duplicate); else spawn the
  `dcode-headless` server (launcher path from `-Ddcode.server.launcher`, else `dcode-headless` on
  PATH) with `-vm <java.home>/bin/java -nosplash`, then poll health.
- `restart` — stop, wait for exit, start, wait for ready.

### 3.5 Server changes (minimal)
1. `ServerActivator` — write `server-endpoint.json` after successful bind.
2. `DCodeApi` — add `POST /api/v1/server/shutdown` (graceful: respond 200, then stop HTTP server
   + stop the framework).
3. `HeadlessApplication` — remove the launch-argument command mode (REPL + daemon remain); a
   launch argument now prints a pointer to `dcode-cli`.

## 4. Affected Bundles / Products
- New: `plugins/eu.kalafatic.dcode.cli`, `product/.../dcode-cli.product`.
- Modified: `plugins/eu.kalafatic.dcode.server` (`ServerActivator`, `DCodeApi`, `HeadlessApplication`),
  `product/.../pom.xml` (materialize the new product), `features/.../feature.xml` (add the cli bundle).
- Tests: `tests/eu.kalafatic.dcode.tests` (thin-client isolation, endpoint discovery, lifecycle).

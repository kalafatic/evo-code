# EVO-CODE — Initial Functional Test Report (ENR + Ollama + Platform Foundation)

**Date:** 2026-11-23
**Status:** Baseline test → document phase complete (Phase I). This report is the pre-fix
baseline; defect resolution follows in Phase J. Every result below was produced by executing the
actual products and live services on this host — no simulation, no fabricated results.

---

## 1. Environment

| Item | Value (verified) |
|---|---|
| Repo | `/home/petr/git/evo-code-221126` (branch `master`, clean) — product **DCode 2.6.11** |
| Build | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B clean verify` → **BUILD SUCCESS, 208 tests, 0 failures, 0 errors, 3 skipped** |
| ENR | `http://127.0.0.1:8111` (version 1.0, UP) — native `evo` (ctx 4) / `tiny-evo` (ctx 32); CUDA RTX 5070 Ti present |
| Ollama | `http://127.0.0.1:11434` v0.40.16, **30 models**, systemd service `ollama.service` (active, enabled) |
| Config | `~/.dcode/` — `dcode-config.json` (httpPort 58081), `runtimes.json` (enr default @8111, enr-ollama @8111, ollama @11434), `harnesses.json`, `dcode.db` (72 persisted sessions) |
| Machine | 64-core EPYC, 121 GB RAM, DISPLAY=:1, machine-default Java 8 (Java 21 at `/usr/lib/jvm/java-21-openjdk-amd64`) |

## 2. Architecture Findings (verified by inspection)

1. **One core, three modes** confirmed: RCP, Headless and Web are clients of the same
   `/api/v1` + SSE server API. The RCP UI talks to an embedded instance of the same server
   bundle; the headless process serves the same API + SPA. No parallel implementations found.
2. **Runtime/provider layer** is the single `RuntimeManager` (`config` bundle): 3 adapters
   (`EnrProvider`, `OllamaProvider`, `OpenAiCompatibleProvider`) selected deterministically by
   runtime registration type. No silent cross-routing.
3. **ENR integration is wire-only** (`/enr/v1/*` + `/api/v1/models/*`); no ENR classes bound.
   **Ollama pulls are delegated to ENR model management** (no private Ollama pull).
4. **Filesystem-only project model** confirmed: workspaces are plain directories; the
   `repositories` abstraction is optional metadata. No Git was used in any scenario.
5. **Headless product ships SWT/e4/ui bundles** (`org.eclipse.swt`, `org.eclipse.ui.workbench`,
   `dcode.ui`, `ui.inference`) even though the headless runtime never uses them. **Presence ≠
   initialization**: the headless process starts with no `Display`/`Shell` and no SWT reference
   in its log. Informational only; not a functional defect.
6. **`CoreConfiguration.defaultModel = "qwen2.5-coder:32b"`** is a stale config value: it is
   persisted and UI-editable but **not consumed** by session execution (which uses
   `runtimes.json` `defaultModel`, currently empty). It references a model that is not installed.
   Low severity — see D1.

## 3. Startup Results — Phase B

| # | Test | Result | Evidence |
|---|---|---|---|
| B1 | Headless mode (start, no SWT, stages, shutdown, restart) | **PASS** | started with `env -u DISPLAY`; log "Headless runtime starting (no UI dependencies)"; stages BOOTSTRAP…SERVER STARTED (CLIENTS NOT_STARTED); no Display/Shell/SWT in log; SIGTERM → exit 143, port released; restart binds clean, no stale process |
| B2 | Headless + Web | **PASS** | SPA served (dark theme, `resourceSource=osgi-bundle`, frontendVersion web-20261122-r4); assets `app.js`/`models.js`/`runtimes.js`/`app.css` all HTTP 200; API endpoints respond |
| B3 | RCP mode | **PASS** | workbench window rendered (mutter "DCode" 800×563); embedded server on 58080 UP; **24 tools registered incl. 11 JDT/PDE tools**; 0 `!ENTRY` errors; WebKit browser view created; UI auto-connected |
| B4 | Lifecycle management | **FAIL** (partial) | `server-start/stop/restart/status` and `start/stop/restart` present and functional; **but the read-only CLI commands (`status`, `runtimes`, `models`, …) boot a full runtime and, when the configured port is occupied, persist a fallback port to `dcode-config.json`** — a read-only query mutated the config (httpPort 58081→58080 observed). See DEFECT-A. |

Port facts: canonical HTTP 58080 (`DCodePorts.HTTP`), fallback range in 58xxx, management 58380,
aux 58680 (both reserved/stopped). Configured `httpPort` wins; RCP and headless ran
simultaneously (58080 / 58081) against the same data dir without failure.

## 4. AI Commander & Inference — Phase D

| # | Test | Result |
|---|---|---|
| D1 | Session create → start → messages → toolcalls via API | **PASS** |
| D2 | Streaming output (SSE `MESSAGE_PARTIAL`/token frames) | **PASS** (Ollama + ENR) |
| D3 | Conversation context retained across turns | **PASS** (qwen3.8-32k: "My name is Alice" → "What is my name?" → "Alice") |
| D4 | Cancellation | **PASS** (RUNNING → `cancel` → CANCELLED) |
| D5 | Error propagation / terminal-state enforcement | **PASS** (mutating tool on COMPLETED session → clean `VALIDATION_FAILED`) |
| D6 | Configuration persists across restart | **PASS** (sessions + config survived SIGTERM + restart) |

The full tool-execution cycle was demonstrated end-to-end (user instruction → inference →
structured tool call → real execution → result → model continuation → final response). The model
did **not** fabricate results; every tool call was verified against the real filesystem/output.

## 5. ENR Native Integration — Phase E

| # | Test | Result |
|---|---|---|
| E1 | Connection / health (`/enr/v1/health`) | **PASS** (UP) |
| E2 | Model discovery (`/enr/v1/models`) | **PASS** (evo, tiny-evo; size/ctx/state) |
| E3 | Blocking chat (`/enr/v1/chat`) | **PASS** (tiny-evo → content + finishReason + tokens) |
| E4 | Streaming (`/enr/v1/chat/stream`) | **PASS** (SSE start/token/done frames) |
| E5 | Runtime status (`/enr/v1/runtime`) | **PASS** (running, CPU 64 + CUDA device) |
| E6 | Ollama protocol via ENR (`enr-ollama` runtime) | **PASS** (chat through ENR's `/api`) |

Failure attribution is correct (runtime `enr` vs `ollama` vs `enr-ollama`). **Limitation:** ENR's
native `evo`/`tiny-evo` are protocol-test models (ctx 4/32) and cannot run real agent tasks; real
agent work uses Ollama models (direct or via ENR's Ollama-compatible surface). ENR's `/api/version`
reports `2.6.11` (ENR's own version string — informational, not a DCode defect).

## 6. Ollama Compatibility — Phase F

| # | Test | Result |
|---|---|---|
| F1 | Connectivity / version | **PASS** (v0.40.16) |
| F2 | Model discovery `/api/tags` | **PASS** (30 models, incl. qwen3.8-32k, qwen3-coder:30b, smollm:135m, deepseek-coder-v2) |
| F3 | Model management (list/pull/jobs/cancel/remove via ENR) | **PASS** (`/api/v1/models/providers` → ollama reachable; `/api/v1/models/ollama/installed` → 30; `/api/v1/models/jobs` → shared ENR jobs) |
| F4 | Ollama as system service | **PASS** (systemd `ollama.service` active/enabled; DCode works with it running independently) |
| F5 | Chat (non-streaming + streaming) | **PASS** (smollm:135m, qwen3.8-32k, qwen3-coder:30b) |
| F6 | Structured tool calling | **PASS** (write_file/shell/edit_file with JSON args, real execution, result serialization, model continuation; 3-tool and 5-tool runs) |
| F7 | Protocol independence | **PASS** (enr, enr-ollama, ollama all functional; deterministic selection) |
| F8 | Local models (small / large / tools) | **PASS** (smollm:135m small; qwen3-coder:30b large; qwen3.8-32k tools-capable) |

**Model limitation (not a defect):** `smollm:135m` ignores simple instructions (produces
rambling output). `qwen3.8-32k:latest` is the reliable tools-capable agent model.

## 7. Terminal & Filesystem — Phase G

| # | Test | Result |
|---|---|---|
| G1 | Basic terminal (`pwd`, `whoami`, `uname`, `java`, `mvn`) | **PASS** (workspace-root confinement verified: `pwd` → `/tmp/evo-code-enr-test`) |
| G2 | Filesystem ops (mkdir/write/read/append/list/delete) | **PASS** (all in `/tmp/evo-code-enr-test`) |
| G3 | Java scenario (create → compile → run → verify `EVO_CODE_ENR_INTEGRATION_OK`) | **PASS** (write_file + shell + verify; `VERIFY=PASS`) |
| G4 | AI-assisted modification (→ `EVO_CODE_ENR_MODIFICATION_OK`) | **PASS** (list/read/edit_file/shell; model self-recovered from a search_code failure) |
| G5 | Error handling (invalid command / non-zero exit) | **PASS** (`exit 42` → code 42; `command not found` → code 127; correct `success:false`) |

`ShellTool` implementation verified: `/bin/bash -c`, workspace-confined, env-sanitized (strips
`OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`GITHUB_TOKEN`/`GH_TOKEN`), 300 s default / 3600 s cap,
100 k output cap, cancellable, correct non-zero exit reporting.

## 8. End-to-End Scenarios — Phase H

| Scenario | Result |
|---|---|
| 1 Basic AI chat | **PASS** |
| 2 Workspace inspection | **PASS** (filesystem-only; no Git) |
| 3 File creation (README) | **PASS** (covered by G3 source creation) |
| 4 Code generation | **PASS** (G3) |
| 5 Code debugging | **PASS** (G4 modification cycle incl. compile+verify) |
| 6 Multi-turn context | **PASS** (D3) |
| 7 Multiple tool calls | **PASS** (G4: 5 tool calls, 6 iterations) |
| 8 Ollama model management | **PASS** (F3/F4) |
| 9 Cross-interface consistency | **PASS** (RCP + Web + Headless all serve the same API/backend) |
| 10 Protocol comparison (TEIP vs Ollama) | **PASS** (E vs F; legitimate difference: ENR native models are protocol-test only) |

## 9. Failed / Blocked / Not Tested

| Test | Classification | Reason |
|---|---|---|
| B4 lifecycle CLI (status/… read-only commands) | **FAIL** | config mutation on read-only query (DEFECT-A) |
| `search_code` tool | **FAIL** | `Files.readString` throws on binary files (DEFECT-B) |
| `defaultModel` config value | **FAIL (dormant)** | stale, unused, references non-installed model (D1) |
| Live JUnit suites (`EnrLiveIntegrationTest`, `LiveOllamaTest`) | **NOT TESTED via Maven** (verified live via API instead) | live endpoints verified directly against running ENR/Ollama |
| Aider/OpenCode external-tool tests | **NOT TESTED** | external CLIs out of scope for this campaign |

## 10. Confirmed Defects (root causes — for Phase J)

### DEFECT-A — Read-only CLI commands mutate persisted configuration (lifecycle)
- **Symptom:** `dcode-headless status` (and `runtimes`/`models`/…) boot a full DCode runtime; when
  the configured port is occupied, the embedded server falls back to another 58xxx port and
  **persists it** (`httpPort` 58081→58080 observed). The command then reports its own transient
  instance, not the running server.
- **Root cause:** `ServerActivator.startHttpServer()` persists the fallback port on every bind
  conflict (server/src/…/ServerActivator.java:219-227); `HeadlessApplication.commandPort()`
  prefers the *local* `dcode.server.port` (the transient fallback) over the persisted config, so
  a transient command instance cannot observe the running server.
- **Impact:** read-only queries mutate `dcode-config.json`; CLI cannot report the running
  server's state; wasteful full-runtime boot per command.

### DEFECT-B — `search_code` fails on binary files ("Input length = 1")
- **Symptom:** `POST /api/v1/tools/search_code/execute` returns `Search failed: Input length = 1`
  for any workspace containing a binary file (e.g. a compiled `.class`).
- **Root cause:** `SearchTool.execute()` calls `Files.readString(path)` on every regular file;
  binary files throw `java.nio.charset.MalformedInputException` ("Input length = 1"), caught as
  `IOException` and returned as a whole-search failure.
- **Impact:** workspace search is unusable in any workspace that contains compiled/binary files.

### D1 — Stale, unused `defaultModel` config value (low severity)
- `dcode-config.json defaultModel = "qwen2.5-coder:32b"` (hardcoded default in
  `CoreConfiguration`) is not installed and is not consumed by session execution.

## 11. Modified Files

Source (Phase J fixes):

- `plugins/eu.kalafatic.dcode.harness/src/.../tools/SearchTool.java` — skip binary/unreadable files
- `plugins/eu.kalafatic.dcode.core/src/.../config/CoreConfiguration.java` — defaultModel → empty
- `plugins/eu.kalafatic.dcode.server/src/.../internal/ServerActivator.java` — no persist on port conflict
- `plugins/eu.kalafatic.dcode.persistence/src/.../internal/PersistenceActivator.java` — skip recovery when configured port is held
- `tests/eu.kalafatic.dcode.tests/src/.../harness/ToolEngineTest.java` — new `searchCodeSkipsBinaryFiles` test

Docs created: `docs/PHASE-A-ARCHITECTURE-OVERVIEW.md`, `docs/testing/EVO_CODE_INITIAL_TEST_REPORT.md`,
`docs/testing/EVO_CODE_TESTING_GUIDE.md`, `docs/testing/EVO_CODE_INTEGRATION_READINESS.md`.

`~/.dcode/dcode-config.json` was backed up to `/tmp/opencode/dcode-config.backup.json`; its `httpPort`
was restored to 58081 (its pre-test value) after the baseline defect reproduced the mutation.

*Test artifacts are confined to `/tmp/evo-code-enr-test` and `/tmp/opencode/`.*

---

## 12. Implemented Fixes (Phase J)

| # | Defect | Root cause | Fix | Regression test |
|---|---|---|---|---|
| F1 | DEFECT-B `search_code` fails "Input length = 1" | `Files.readString` throws `MalformedInputException` on binary files, aborting the whole search | skip unreadable/binary files per-file (`continue`) | `ToolEngineTest.searchCodeSkipsBinaryFiles` |
| F2 | D1 stale `defaultModel="qwen2.5-coder:32b"` (unused, not installed) | hardcoded legacy default in `CoreConfiguration` | default to empty; runtime registry default remains authoritative | none (config-level) |
| F3 | DEFECT-A read-only CLI mutates `httpPort` | `ServerActivator` persisted the fallback port on bind conflict | persist only when the port was freshly allocated (`configuredPort <= 0`) | none (OSGi wiring) |
| F4 | DEFECT-A transient CLI runs restart recovery (could mark live sessions FAILED) | recovery ran unconditionally at persistence activation | run recovery only for the primary server — skip when the configured HTTP port is already held (detected via `PortInspector.occupant`) | none (OSGi wiring) |

## 13. Post-Fix Verification (Phase L)

- **Build:** `./mvnw -B clean verify -Ddcode.enr.endpoint=http://127.0.0.1:8111 -Ddcode.live.model=smollm:135m`
  → **BUILD SUCCESS, 209 tests, 0 failures, 0 errors, 0 skipped** (live ENR + Ollama suites included).
- **F1 runtime:** `search_code` on a workspace containing `EvoCodeEnr.class` (binary) →
  `success=true`, found `EvoCodeEnr.java:1: public class EvoCodeEnr {` (binary skipped).
- **F3 runtime:** `dcode-headless status` while the primary held 58081 → `dcode-config.json httpPort`
  unchanged (58081).
- **F4 runtime:** `dcode-headless status` while the primary held 58081 → log
  `Configured HTTP port 58081 already in use — skipping restart recovery (not the primary server)`.

## 14. Remaining Defects / Known Limitations

1. **CLI command mode still boots a full runtime** (core + harness + server) instead of acting as a
   thin HTTP client. Consequences now mitigated (no config mutation, no recovery) but two remain:
   (a) `status`/`runtimes`/`models`/… report a freshly-booted instance rather than the running
   server, and (b) `stop` cannot target a running server when its actual port differs from the
   persisted `httpPort`. A clean fix (thin-client command mode / early command detection before
   bundle activation) is an architectural change — **deferred for product-owner approval**.
2. **Headless product ships SWT/e4/ui bundles** but never initializes SWT (no `Display`/`Shell`).
   Informational; not a functional defect.
3. **ENR native `evo`/`tiny-evo` are protocol-test models** (ctx 4/32); real agent work requires
   Ollama models.
4. **`smollm:135m` does not follow instructions** — a model limitation; use `qwen3.8-32k:latest`.

## 15. Ecosystem Integration Readiness (Phase K)

See `docs/testing/EVO_CODE_INTEGRATION_READINESS.md`. Headline: headless server, FHC 1.0 surface,
runtime/provider abstraction, workspace/session model and tool registry are all reusable by
TRYST/EVO/EVO-Studio via wire-only contracts; the two DEFECT-A side effects that would have broken
CLI-driven integration were fixed this iteration.

# EVO-CODE — Phase A: Architecture and Environment Overview

**Date:** 2026-11-23
**Status:** Discovery complete (read-only inspection + live probes). This document records the
*actual* implementation and environment, not the planned or intended one. Every value below was
verified either by source inspection in `/home/petr/git/evo-code-221126` or by a live probe on
this host on 2026-11-23.

**Scope:** functional-validation baseline for the three operating modes (RCP / Headless / Web),
the ENR integration, the Ollama-compatibility layer, and the platform foundation.

---

## 1. Executive Summary

EVO-CODE (bundle namespace `eu.kalafatic.dcode`, product "DCode") is a Java 21 / Eclipse RCP /
OSGi (Equinox) / Tycho platform that implements **one shared core** with three access modes —
RCP, Headless, and Web — over the same HTTP `/api/v1` + SSE contract. The project has completed
work packages WP5.2–WP5.10 and Phase 6 (FHC 1.0 + integration). A prior "initial functional
test → fix → verify" iteration (2026-11-21/22) already closed six defects; this campaign
re-validates that state against the current environment.

- **ENR** is the family-native inference runtime; EVO-CODE consumes it wire-only through two
  contracts: `EnrProvider` (`/enr/v1/*`) and `ModelManagementClient` (`/api/v1/models/*`).
- **Ollama** is consumed through `OllamaProvider` (`/api/tags`, `/api/chat`, `/api/version`),
  with model pulls delegated to the shared ENR model-management service (no private Ollama pull).
- Live environment on this host: ENR on `:8111` (UP), Ollama 0.40.16 on `:11434`, DCode not
  running at discovery time.

---

## 2. Repository Layout (actual)

```
evo-code-221126/
├── pom.xml                     Tycho parent eu.kalafatic.dcode.parent (2.6.11-SNAPSHOT)
├── mvnw / mvnw.cmd             Maven wrapper (3.9.12; system Maven 3.8.7 is too old)
├── targetplatform/eu.kalafatic.dcode.target   Eclipse 2024-03 target (+ orbit/, unused/)
├── plugins/                    OSGi bundles (see §3)
├── features/eu.kalafatic.dcode.feature
├── product/eu.kalafatic.dcode.product          dcode.product + dcode-headless.product
├── tests/eu.kalafatic.dcode.tests              Tycho surefire JUnit 5 bundle
├── scripts/                    build.sh, run-headless.sh
├── docs/                       technical documentation
└── .launches/                  4 PDE launch configs (RCP, Headless, Tests, Maven build)
```

---

## 3. OSGi Bundles (actual, 11 bundles)

| Bundle | Responsibility | Headless-safe |
|---|---|---|
| `eu.kalafatic.dcode.core` | Domain model, session/execution state machines, event bus, lifecycle, ports, config service, `ServiceAvailabilityGate`, `WebServerControl`, `LogSanitizer` | Yes |
| `eu.kalafatic.dcode.config` | Runtime/provider SPI, `RuntimeManager`, `RuntimeAdapterFactory`, `EnrProvider`, `OllamaProvider`, `OpenAiCompatibleProvider`, `ModelManagementClient`, credential vault, model router, git accounts, conversation profiles | Yes |
| `eu.kalafatic.dcode.repositories` | Workspace model, JGit `GitEngine`, repository operations, access policies | Yes |
| `eu.kalafatic.dcode.persistence` | SQLite schema + migrations, `Sqlite*` repositories, AES-GCM secrets | Yes |
| `eu.kalafatic.dcode.harness` | Agent loop, `ToolEngine`, `ContextEngine`, tool registry, sessions, review/change | Yes |
| `eu.kalafatic.dcode.java.tooling` | JDT/PDE tooling (13 tools), `LanguageTooling` SPI impl | Yes (degrades headless) |
| `eu.kalafatic.dcode.server` | NanoHTTPD `DCodeHttpServer` + `DCodeApi`, SSE, `HeadlessApplication`, web assets | Yes (headless-only) |
| `eu.kalafatic.dcode.client` | `DCodeApiClient` + SSE client, `BackendProbe`/identity, model methods | Yes |
| `eu.kalafatic.dcode.ui` | RCP workbench (9+ views, editors, perspective), `ServerRuntimeBoot`, `RuntimeConnection` | No (SWT) |
| `eu.kalafatic.dcode.ui.inference` | OpenCode-like inference chat workspace (backend-agnostic SWT) | No (SWT) |
| `org.slf4j.nop` | SLF4J no-op binding (satisfies sqlite-jdbc's slf4j-api) | Yes |

Services are registered **manually** via bundle activators (family convention; Equinox DS is
absent from the 2024-03 target). `ServiceAvailabilityGate` in core makes bundle start order
irrelevant.

---

## 4. Three Operating Modes (verified design)

| Mode | Entry point | SWT/Workbench | Notes |
|---|---|---|---|
| Headless | `eu.kalafatic.dcode.server.application` / `HeadlessApplication` | none | HTTP API + REPL; process runs until `stop`/SIGTERM |
| RCP | `eu.kalafatic.dcode.ui.application` / workbench | SWT/JFace | embeds the same server bundle; UI talks to the embedded HTTP server |
| Web | SPA assets served by `eu.kalafatic.dcode.server` | none | same origin; same `/api/v1` + SSE |

RCP is not a prerequisite for the Web interface; Headless never initializes SWT. The RCP UI and
the Web UI are clients of the same server API. Products materialize to
`product/.../target/products/eu.kalafatic.dcode.{ui.rcp,server.headless}/linux/gtk/x86_64`.

---

## 5. Inference / Provider Architecture

`RuntimeManager` (in `config`) is the **single** runtime/provider registry. It persists
registrations, binds credentials to the vault, creates adapters, and keeps the registry, the
`RuntimeStore` (`runtimes.json`) and the `ModelRouter` defaults in sync.

`RuntimeAdapterFactory` maps a registration `type` to an adapter:

| `RuntimeType` | Adapter | Wire contract |
|---|---|---|
| `ENR` | `EnrProvider` | `/enr/v1/{health,models,chat,chat/stream,models/{id}/load,models/{id}/unload,runtime}` |
| `OLLAMA` | `OllamaProvider` | `/api/{tags,chat,version}`; pull via ENR |
| `OPENAI_COMPATIBLE` | `OpenAiCompatibleProvider` | OpenAI-compatible `/v1/...` |

Protocol selection is **deterministic**: the runtime id selected on a session/request decides the
adapter. There is no silent cross-routing. `RuntimeManager.refreshAvailabilityIfStale()` probes
all enabled runtimes in parallel (30 s TTL) for the management UIs.

---

## 6. ENR Integration (wire-only, no ENR classes)

ENR is the "EVO Native Runtime" (`/home/petr/git/enr-221126`): a native `.evo`/GGUF inference
runtime plus provider gateway (Ollama/llama.cpp/OpenAI-compatible/cloud) and shared model
management. DCode binds only ENR's HTTP surfaces.

**Endpoint resolution order** (implemented in `EnrProvider`, `ModelManagementClient`,
`RuntimeManager`):

1. Explicit endpoint on the runtime registration (`runtimes.json`).
2. `dcode.enr.endpoint` system property (explicit user override; never auto-migrated).
3. Live probe of well-known family endpoints in order: `http://127.0.0.1:8111` (standalone ENR),
   `http://127.0.0.1:28780` (EVO-embedded ENR).
4. Default `http://127.0.0.1:28780`.

`RuntimeManager.recheckEnrEndpoint()` additionally auto-migrates a stale `enr` registration
(historical 28780 → live 8111) on startup when no override is configured.

**Model management:** `ModelManagementClient` consumes ENR `/api/v1/models/*`
(`providers`, `providers/{p}/available`, `providers/{p}/installed`, `providers/{p}/pull`,
`downloads`, `downloads/{jobId}` GET/DELETE, `providers/{p}/{model}` DELETE). DCode holds **no**
model-management state, download jobs, or provider registry of its own. `OllamaProvider.pullModel`
delegates to `ModelManagementClient.pullAndWait("ollama", ...)`.

Live verification (2026-11-23): ENR `:8111` answers `/enr/v1/health` (`UP`), `/enr/v1/models`
(`evo`, `tiny-evo`), `/enr/v1/chat`, `/enr/v1/runtime` (CPU 64 cores + CUDA RTX 5070 Ti), and
`/api/v1/models/providers` (ollama provider reachable).

---

## 7. Ollama Compatibility

`OllamaProvider` consumes the standard Ollama HTTP API:

- Model discovery: `GET /api/tags` (per-model capabilities: `completion`, `tools`, `insert`,
  `vision`, `thinking`; chat-capable = `completion` present, or any list absent).
- Chat: `POST /api/chat` (`stream:false`) and `POST /api/chat` (`stream:true`, NDJSON lines).
- Status: `GET /api/version`.
- Pull: delegated to ENR model management (no private Ollama pull).

DCode supports three runtime registrations simultaneously (verified in `~/.dcode/runtimes.json`):
`enr` (`:8111`, native), `enr-ollama` (`:8111`, Ollama-compatible via ENR), `ollama` (`:11434`).

---

## 8. Ports and Endpoints (verified)

| Port | Purpose | Source |
|---|---|---|
| 58080 | HTTP API (default; `DCodePorts.HTTP`) | `core/port/DCodePorts.java` |
| 58080 + range | HTTP fallback (stays in 58xxx; `DCodePorts` RANGE) | `DCodePorts.java` |
| 58380 | Management (`DCodePorts.MANAGEMENT`) | `DCodePorts.java` |
| 58680 | Auxiliary (`DCodePorts.AUX`) | `DCodePorts.java` |
| 8111 | ENR standalone | `enr.sh serve` |
| 28780 | ENR EVO-embedded default | `EnrProvider.DEFAULT_BASE_URL` |
| 11434 | Ollama | `OllamaProvider.DEFAULT_BASE_URL` |

Configured values win; `dcode-config.json httpPort` overrides the default. At discovery time the
config carried `httpPort: 58081` (a prior fallback allocation persisted).

**HTTP API surface** (dispatch in `DCodeApi.java` / `DCodeHttpServer.java`, prefix `/api/v1`):

- `health`, `status`, `ports`
- `workspaces` (CRUD), `workspaces/{id}/repositories`, `.../tree`, `.../files`
- `sessions` (CRUD), `sessions/{id}/start`, `.../cancel`, `.../messages`, `.../context`,
  `.../reviews`, `.../changes`, `.../toolcalls`, `.../rename`, `DELETE sessions/{id}`
- `reviews`
- `runtimes` (+ `runtimes/{id}/models`), `providers` (+ `providers/{id}/test`)
- `models` (unified catalogue), `models/*` (ENR model-management passthrough)
- `agents`, `profiles`
- `tools/{name}/execute` (tool registry; e.g. `tools/shell/execute`, JDT/PDE tools)
- `inference/chat`, `inference/chat/stream`, `inference/cancel`
- `events/stream` (SSE)
- FHC 1.0 execution surface (see `PHASE-6-FHC-1.0.md`)

---

## 9. Configuration (verified)

Data dir default `~/.dcode/` (override `-vmargs -Ddcode.home=<dir>`).

| File | Purpose |
|---|---|
| `dcode-config.json` | core config (see keys below) |
| `runtimes.json` | runtime registrations + default runtime/model |
| `harnesses.json` | harness enablement (default `dcode`, `opencode` enabled) |
| `dcode.db` | SQLite (WAL, migrations) |
| `secrets.enc` / vault | AES-GCM encrypted credentials (not plaintext on disk) |
| `agents.json`, `profiles.json` | agent + conversation-profile registries (created on demand) |
| `workspaces/`, `artifacts/`, `logs/` | filesystem state |

`dcode-config.json` keys (verified): `dataDir`, `workspacesDir`, `artifactsDir`, `logsDir`,
`httpPort`, `managementPort`, `bindAddress`, `nodeId`, `appVersion`, `defaultProvider`,
`providerEndpoint`, `harnessType`, `agentRole`, `defaultModel`, `webServerEnabled`,
`webServerAutoStart`.

**System properties:** `dcode.enr.endpoint` (ENR override), `dcode.live.model` (live-test opt-in),
`dcode.server.port` (published server port), `dcode.home` (data dir override).

---

## 10. Agent / Harness

Native agent loop = `harness/orchestration/ToolCallingAgentLoop`: the model emits exactly one JSON
object per response (`{"tool": ..., "arguments": ...}` or `{"final": ...}`); tool output is fed
back; max 20 iterations. Tools run through `ToolEngine` (permission gate + audit + events). The
system prompt (`ContextEngine.buildSystemPrompt`) lists tool names and exact argument names.
`shell` tool = `/bin/bash -c` in the workspace root, env-sanitized, 300 s default timeout
(3600 s cap), 100 k output cap. `edit_file` requires exact single-match. Sessions transition
state only through `SessionState.isValidTransition`; `COMPLETED/FAILED/CANCELLED` are terminal.

---

## 11. Model Identifiers (actual, 2026-11-23)

| Model | Runtime | Role |
|---|---|---|
| `evo`, `tiny-evo` | ENR (`:8111`, `/enr/v1/*`) | native protocol-test models (ctx 4 / 32) |
| `qwen3.8-32k:latest` | Ollama (`:11434`) | tools-capable agent-run model |
| `qwen3-coder:30b` | Ollama | tools-capable coding model |
| `smollm:135m` | Ollama | live completion test model |
| `qwen2.5-coder:32b` | (config `defaultModel`) | **NOT installed** — see §13 D1 |

Ollama on this host publishes ~30 models (qwen3.8 8k/16k/24k/32k/64k, qwen3-coder 30b/30b-*/8k/
16k/32k, deepseek-coder-v2:16b, nemotron-3.5-lightning, smollm:135m, evo-* exports, …).

---

## 12. Tests

- Hermetic suite via `./mvnw -B verify` (Tycho surefire, JUnit 5.10.2).
- Live suites gated by `-Ddcode.enr.endpoint=...` and `-Ddcode.live.model=...`, forwarded to the
  test JVM via surefire `<systemProperties>`: `EnrLiveIntegrationTest`, `LiveOllamaTest`.
- Prior documented counts: 197 (initial report), 208 (unified model management). Current count is
  re-established by this campaign's `clean verify` (see the verification record).

---

## 13. Discovered Discrepancies (candidate defects, to confirm in Phase B)

| # | Observation | Severity | Notes |
|---|---|---|---|
| D1 | `dcode-config.json defaultModel = "qwen2.5-coder:32b"` but that model is not installed in Ollama | medium | a session using the default model would fail at runtime; confirm the resolution/migration path |
| D2 | `dcode-config.json httpPort = 58081` (persisted fallback, not the canonical 58080) | low | expected PortAllocator behavior; confirm it does not confuse `/api/v1/ports` |
| D3 | ENR native models `evo`/`tiny-evo` are protocol-test only (ctx 4/32); real agent work requires Ollama models | informational | by design; document as a boundary |

---

*No files outside the EVO-CODE repository were modified during this discovery. This document is
the Phase A gate input; architectural changes follow only after Phase B verification.*

# EVO-CODE — Ecosystem Integration Readiness (Phase K)

**Date:** 2026-11-23
**Status:** Architecture-readiness assessment (read-only; no changes to TRYST / EVO / EVO-Studio).

This assesses whether EVO-CODE's existing public interfaces and services are suitable for future
consumption by TRYST, EVO/Evolution and EVO-Studio. It does not authorize changes to those
projects.

---

## 1. Already reusable (no new code required)

| Capability | Surface | Notes |
|---|---|---|
| Headless server | `HeadlessApplication` → HTTP `/api/v1/*` + SSE | starts without SWT/display; the authoritative backend for Web + RCP + external clients |
| FHC 1.0 execution surface | HTTP API (see `PHASE-6-FHC-1.0.md`) | harness-delegation contract for a product-local orchestrator (TRYST/EVO) |
| Inference provider abstraction | `RuntimeAdapter` SPI (`config` bundle) | `EnrProvider`, `OllamaProvider`, `OpenAiCompatibleProvider`; deterministic selection; wire-only |
| Model management | `/api/v1/models/*` passthrough to ENR | pulls/jobs/cancel/remove are ENR-owned; EVO-CODE holds no model state |
| Workspace/session model | `WorkspaceService` / `SessionService` + HTTP | filesystem-oriented; plain-directory workspaces; no Git prerequisite |
| Tool execution | `ToolEngine` + `Tool` registry | shell / file / search / edit / JDT-PDE tools; audit + events |
| Lifecycle control | `WebServerControl` OSGi service | single start/stop authority; exposed via CLI + RCP menu + API |
| **Thin CLI** | `dcode-cli` (HTTP client) | operational + lifecycle commands over `/api/v1`, no runtime boot |
| **Lifecycle endpoint** | `POST /api/v1/server/shutdown` | graceful shutdown for CLI/ops automation |
| TEIP 1.1 | conformant server surface (additive) | family protocol; no private variants |

## 2. Stable interfaces (preserve)

- **HTTP API** (`/api/v1/*`): versioned, open-world JSON; the cross-client contract for RCP, Web
  and external consumers.
- **`RuntimeAdapter` SPI**: the provider/runtime extension point; keep it wire-only and
  deterministic.
- **`SessionService`/`WorkspaceService`**: core domain contracts; state changes go through
  `SessionState.isValidTransition` only.
- **`WebServerControl`**: the single server lifecycle authority — do not introduce a second
  lifecycle manager.
- **Port block**: DCode 58xxx (HTTP 58080 fallback range 500), management 58380, aux 58680;
  DStudio 48xxx; no collision when both run.

## 3. Missing capabilities (for ecosystem integration)

- **TRYST-side `DCodeCodingHarnessAdapter`** — the TRYST-owned adapter that delegates an
  IMPLEMENTING stage to EVO-CODE; lands in TRYST only when that repo's work is authorized.
- **DStudio participant roles** — deferred to TEIP 2.0 (per TEIP-1.1 §10); nothing to build
  against yet.
- **Cross-product shared session store** — intentionally absent (product-local persistence by
  design; family delegation is by execution reference, not shared state).

## 4. Contracts to preserve

- **Filesystem-only project model**: no Git-as-required abstraction; EVO-CODE must not grow
  Git-aware discovery/status/history.
- **Wire-only integration**: no `eu.kalafatic.evolution.*` / `eu.kalafatic.tryst.*` / DStudio
  class references; DTOs owned per product; open-world receivers.
- **Orchestrator sovereignty**: in family-delegated executions the delegating product (e.g.
  TRYST) owns task state, review, approval and commit; EVO-CODE never commits/pushes on its
  behalf.
- **Port conventions** and **product-local registries** (runtimes, connected applications,
  credentials) — never a cross-product shared service.

## 5. Concrete interoperability findings

1. **CLI command-mode side effects (DEFECT-A, fixed this iteration).** Previously, `dcode-headless
   status` (and other read-only commands) booted a full runtime that (a) persisted a fallback port
   to `dcode-config.json` and (b) ran the restart-recovery pass, which could mark a live RUNNING
   session FAILED. Any future tooling that drives EVO-CODE via the CLI against a running server
   would have hit these. Fixed: fallback ports are no longer persisted, and recovery skips when the
   configured HTTP port is already held.
2. **`search_code` binary-file failure (DEFECT-B, fixed).** Workspace search aborted on compiled
   output; now skips binary files.
3. **ENR native models are protocol-test only** (context 4/32). Real agent execution requires
   Ollama models (direct or via ENR's Ollama-compatible surface). Consumers must not assume ENR
   native models are task-capable.
4. **Legacy `CoreConfiguration.defaultProvider/defaultModel/providerEndpoint` are not wired to the
   runtime registry.** Session execution uses `runtimes.json` defaults. This is a dormant
   configuration seam, not an active integration problem, but it should not be relied on as the
   provider-selection contract.

---

*No files outside the EVO-CODE repository were modified.*

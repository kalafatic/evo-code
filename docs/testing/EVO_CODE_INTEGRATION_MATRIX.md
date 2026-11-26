# EVO-CODE — Integration Matrix

**Date:** 2026-11-24
**Scope:** Actual integration points and wire contracts observed during the end-to-end test. All
endpoints and interfaces were verified by source inspection and/or live request.

---

## 1. Product map

| Component | Repo | Port | Role |
|---|---|---|---|
| evo-code (EVO-CODE / DCode) | `evo-code-221126` | 58080 (canonical; running 58081) | shared coding engine + Web + RCP + thin CLI |
| ENR | `enr-221126` | 8111 | native runtime + provider gateway + model management |
| DeepSeek (the "configured LLM") | cloud | `api.deepseek.com/v1` | the actual LLM inference (reachable via ENR) |
| EVO / Darwin | `evolution-221126` | 28080 | family orchestrator; DCode harness adapter |
| TRYST | `tryst-221126` | 38080 | async coding platform; DCode harness adapter |
| Ollama | local | 11434 | local models (qwen3.8-32k etc.) |
| `llm.evo` | — | — | **does not exist** (no code/DNS/service) |

## 2. evo-code → ENR (inference)

| Surface | evo-code adapter | ENR endpoint | Notes |
|---|---|---|---|
| Native ENR | `EnrProvider` | `/enr/v1/{health,models,chat,chat/stream,models/{id}/load,models/{id}/unload,runtime}` | native models only (`evo`/`tiny-evo`, protocol-test) |
| Ollama-compatible | `OllamaProvider` | `/api/{tags,chat,version}` | local Ollama models |
| OpenAI-compatible | `OpenAiCompatibleProvider` | `/v1/{models,chat/completions}` | **DeepSeek routed here** via `deepseek:<model>` |

## 3. ENR → DeepSeek (the "llm.evo" hop)

- `POST /v1/chat/completions` `{"model":"deepseek:deepseek-flash", …}` → DeepSeek
  (`chatcmpl-…` id, `usage` token counts). Verified live.
- `GET /v1/models` exposes `deepseek:deepseek-flash` + `deepseek:deepseek-v4-pro` (+ native).
- ENR's `/enr/v1/chat` does **not** route cloud models (returns `Model not loaded`).

## 4. Caller → evo-code (delegation)

### 4.1 Darwin → evo-code (Scenario A)

| Element | Value |
|---|---|
| Adapter | `evolution-221126/…/harness/DCodeCodingHarnessAdapter.java` |
| Client | `…/harness/DCodeClient.java` |
| Default endpoint | `http://localhost:48080` (**wrong** — evo-code is 58080) |
| Wire | `POST /api/v1/workspaces`, `POST /api/v1/workspaces/{id}/repositories`, `POST /api/v1/sessions`, `POST /api/v1/sessions/{id}/start`, `GET /api/v1/sessions/{id}` (+ messages/changes) |
| Capability | `DCodeFamilyCapability` (`family.dcode.harness`; contracts `harness.delegation`, `harness.discovery`) |

### 4.2 TRYST → evo-code (Scenario C)

| Element | Value |
|---|---|
| Adapter | `tryst-221126/…/infrastructure/dcode/DCodeCodingHarnessAdapter.java` |
| Client | `…/infrastructure/dcode/DCodeHttpClient.java` |
| Default endpoint | adapter `http://localhost:58080` (correct); client fallback `http://localhost:48080` (**wrong**) |
| Wire | same `/api/v1` workspace/session/start/messages/changes/cancel sequence |
| Sovereignty | TRYST owns task lifecycle; DCode never commits/pushes; `WAITING_FOR_APPROVAL` ends delegation |

### 4.3 Web / CLI → evo-code

| Surface | Endpoint |
|---|---|
| Web UI | SPA served by the server bundle; drives `/api/v1/*` + SSE `events/stream` |
| Thin CLI | `dcode-cli` → `/api/v1/{status,ports,runtimes,providers,models,sessions,agents}` + `POST /api/v1/server/shutdown` |

## 5. ID / correlation propagation

| ID | Produced by | Propagated to |
|---|---|---|
| DCode session ID | evo-code (`POST /api/v1/sessions`) | returned to caller (Darwin/TRYST/Web); used for poll/cancel |
| DCode workspace ID | evo-code (`POST /api/v1/workspaces`) | session binding; caller-visible |
| TRYST task ID | TRYST | **not** propagated into DCode (DCode session is a fresh id) |
| ENR request ID | ENR | not threaded back |
| DeepSeek `chatcmpl-…` | DeepSeek | not threaded back |

**Gap:** there is no end-to-end correlation chain (caller task id ↔ DCode session id ↔ ENR request id
↔ DeepSeek completion id). Only the DCode session ID is currently the cross-boundary handle.

## 6. Model selection

- evo-code session selects `providerId` + `modelId` (runtime registry).
- DeepSeek reachable via an OpenAI-compatible runtime pointing at `http://127.0.0.1:8111/v1` with
  model `deepseek:deepseek-flash` (or `-v4-pro`).
- Ollama tools-capable agent model: `qwen3.8-32k:latest` (follows the JSON tool-call protocol;
  used historically for agent runs).
- No OpenAI-compatible runtime for DeepSeek was pre-configured in `runtimes.json`; it must be
  registered (one `POST /api/v1/runtimes`), which this test did.

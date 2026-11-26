# EVO-CODE — End-to-End Coding Integration Test Report

**Date:** 2026-11-24
**Status:** Functional integration test across Darwin / Web / TRYST paths. Real HTTP, real ENR,
real DeepSeek inference, real filesystem changes. No mocks for end-to-end paths.

---

## 0. Executive Summary

evo-code **does** perform real coding tasks through the complete pipeline
`[caller] → evo-code → ENR → DeepSeek → code changes → [caller]`. The pipeline was verified
end-to-end for the evo-code backend/API path (which the Web UI drives). The term **`llm.evo` does
not exist** in any repository or service — the configured LLM is **DeepSeek** (via ENR's
OpenAI-compatible `/v1` surface). A real defect (DeepSeek's DSML function-call format vs. evo-code's
JSON tool-call parser) was found and fixed.

**Overall: PARTIALLY READY** (see §10 — Scenario A and C were not verified end-to-end due to a
wrong default port in Darwin's adapter and TRYST not running).

---

## 1. Environment (verified)

| Component | State |
|---|---|
| evo-code headless | `127.0.0.1:58081` UP (config `httpPort` 58081) |
| ENR | `127.0.0.1:8111` UP (native `/enr/v1`, Ollama `/api`, OpenAI `/v1`, model mgmt `/api/v1`) |
| ENR configured provider | **DeepSeek** (`api.deepseek.com/v1`, models `deepseek-flash`, `deepseek-v4-pro`, connected) |
| Ollama | `127.0.0.1:11434` (30 models) |
| EVO/Darwin | `127.0.0.1:28080` UP (EvolutionServer web) |
| TRYST | **not running** |
| `llm.evo` | **does not exist** (no code, no DNS, no service) |

## 2. Scenario B — Web/API → evo-code → ENR → DeepSeek (core) — **PASS**

The core coding pipeline was verified through evo-code's HTTP API (the same backend the Web UI
drives):

| Step | Result |
|---|---|
| evo-code → ENR inference | `POST /api/v1/inference/chat` runtime `enr-openai` (`http://127.0.0.1:8111/v1`) model `deepseek:deepseek-flash` → `EVO_CODE_DEEPSEEK_OK` (DeepSeek `usage`: 39 prompt / 18 completion tokens) |
| Model discovery | `/api/v1/runtimes/enr-openai/models` lists `deepseek:deepseek-flash`, `deepseek:deepseek-v4-pro` (+ ENR native `evo`/`tiny-evo`) |
| Coding task | workspace `/tmp/evo-code-e2e-test` + session + `POST /sessions/{id}/start` ("add subtract() and verify") |
| Real code change | `Calculator.java` gained `public int subtract(int a, int b) { return a - b; }` (via `edit_file`) |
| Real execution | `javac Calculator.java CalculatorMain.java && java CalculatorMain` → `subtract(10,4)==6` verified (via `shell`) |
| Tool calls | 6 calls: `search_code`, `read_file`, `edit_file`, `read_file`, `write_file`, `shell` |
| Final result | accurate `{"final": …}` summary returned; session `COMPLETED` |

**Root cause fixed during this test:** DeepSeek emits tool calls in its native DSML markup
(`<｜DSML｜ invoke name="shell">`) rather than the JSON the harness instructs. On the 5th tool call
the model fell back to DSML and the loop failed with *"neither a tool call nor a final message"*.
Fix: `ToolCallingAgentLoop` now normalizes DSML to the JSON action shape (regression test
`AgentLoopResponseHandlingTest.dsmlToolCallIsParsedAndExecuted`).

**Not verified via a real browser session** (no browser automation): the Web UI itself is a thin
client over the same `/api/v1` surface (verified serving + assets in the prior campaign); the
backend path the UI drives is fully verified here.

## 3. Scenario A — Darwin → evo-code — **PARTIAL**

- Darwin **implements** `eu.kalafatic.evolution.controller.harness.DCodeCodingHarnessAdapter` +
  `DCodeClient` (Phase 6 WP-6.8), delegating via the same `/api/v1` surface (workspace → session →
  start → messages → changes).
- Darwin (EvolutionServer) is **running** on `28080`.
- **Defect:** `DCodeCodingHarnessAdapter.DEFAULT_ENDPOINT = "http://localhost:48080"` — the wrong
  port (48080 is the DStudio block; evo-code is 58080). With the default, Darwin would target an
  unreachable endpoint.
- Full end-to-end (Darwin DEVELOPER mode routing a task to DCode) was **not exercised** — it
  requires Darwin's DEVELOPER mode to be configured to select the DCode harness and a live task
  through the Darwin UI/API.

## 4. Scenario C — TRYST → evo-code — **BLOCKED (environment)**

- TRYST **implements** `eu.kalafatic.tryst.infrastructure.dcode.DCodeCodingHarnessAdapter` +
  `DCodeHttpClient` (Phase 6 WP-6.6), delegating IMPLEMENTING to evo-code and collecting the diff.
- **TRYST is not running** (no `38080`), so the distributed workflow (TRYST Web → RCP Server →
  evo-code → ENR → DeepSeek → back) could not be executed end-to-end.
- The evo-code side of that flow (workspace/session/start/messages/changes over `/api/v1`) is the
  exact path verified in Scenario B.
- **Inconsistency:** `DCodeCodingHarnessAdapter.DEFAULT_ENDPOINT = "http://localhost:58080"`
  (correct) but `DCodeHttpClient`'s own fallback is `"http://localhost:48080"` (wrong).

## 5. Cross-scenario findings

- **Session isolation:** sessions are keyed by workspace and persisted in SQLite; the DCode
  session ID is the correlation handle returned to callers. (Concurrency not stress-tested here.)
- **Inference consistency:** all paths use ENR; DeepSeek is the only cloud provider configured.
  Ollama local models (`qwen3.8-32k`) remain the alternative for the JSON-tool-call agent loop.
- **Failure handling:** a failed/empty agent response marks the session `FAILED` (verified); the
  prior-campaign recovery logic transitions interrupted `RUNNING`/`WAITING_FOR_APPROVAL` sessions to
  `FAILED` on restart (observed: 3 sessions recovered at startup).

## 6. Missing telemetry / observations

1. **No end-to-end correlation-ID propagation** beyond the DCode session ID: the ENR request ID and
   the DeepSeek chat-completion ID (`chatcmpl-…`) are not threaded back into the DCode session or
   the caller's task ID.
2. **`/api/v1/sessions/{id}/changes` is repository-based**: for a filesystem-only workspace (no
   registered repository) it returns `{"repositories":[]}` even though files were modified — the
   change/diff reporting requires a repository (TRYST's adapter adds one; the filesystem-only model
   does not).
3. **Token usage** is captured at the DeepSeek/ENR layer but not surfaced in the DCode session
   result.
4. **No duplicated inference** was observed in the single-caller path.

---

*Evidence: live HTTP requests against 58081/8111, real `/tmp/evo-code-e2e-test` filesystem changes,
server logs preserved under `/home/petr/.local/share/opencode/shell/`.*

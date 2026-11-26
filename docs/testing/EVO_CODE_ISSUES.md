# EVO-CODE — Identified Issues

**Date:** 2026-11-24
**Scope:** Defects and gaps found during the end-to-end coding integration test.

---

## Fixed during this test

### ISSUE-1 — DeepSeek DSML tool-call format not parsed (HIGH) — **FIXED**
- **Symptom:** a real coding task through `deepseek:deepseek-flash` failed with *"Agent produced
  neither a tool call nor a final message"* after the model fell back to its native DSML
  function-call markup (`<｜DSML｜ invoke name="shell">…`) on the 5th call.
- **Root cause:** `ToolCallingAgentLoop.parseAction()` parsed only plain JSON
  (`{"tool":…,"arguments":…}` / `{"final":…}`). DeepSeek's native function-calling format is DSML.
- **Fix:** `ToolCallingAgentLoop` now normalizes DSML `invoke`/`parameter` markup to the JSON action
  shape (with a fallback to individual `<parameter>` entries). Regression test added.
- **Verification:** the task now completes (6 tool calls; real `subtract()` added, compiled, run).

### ISSUE-2 — Stale `OsgiWiringTest` after git-tool removal (LOW) — **FIXED**
- **Root cause:** `sync-241126-1` removed `git_status`/`git_diff`/`git_commit` from the harness
  (filesystem-only alignment) but left `OsgiWiringTest.builtinToolsRegistered` asserting
  `git_commit`. Build failed.
- **Fix:** updated the test to assert the current tool set (`write_file`, `shell`, `run_build`) and
  removed the stale `git_commit` assertion; removed the stale `git_commit` reference from
  `ContextEngine`'s system prompt.

## Open issues (not fixed — out of scope or cross-repo)

### ISSUE-3 — `llm.evo` does not exist (BLOCKER for the literal spec)
- The mission's "llm.evo" is not a component: no code, no DNS, no service. The actual configured LLM
  is **DeepSeek** via ENR. Recommendation: clarify whether "llm.evo" is shorthand for the configured
  DeepSeek provider, or a planned component to be implemented.

### ISSUE-4 — Wrong default endpoint in Darwin's DCode adapter (HIGH, cross-repo)
- `DCodeCodingHarnessAdapter.DEFAULT_ENDPOINT = "http://localhost:48080"` (48080 is the DStudio
  block; evo-code is 58080). With the default, Darwin targets an unreachable endpoint.
- Also in TRYST: `DCodeHttpClient`'s constructor fallback is `http://localhost:48080` while the
  adapter default is `58080` (inconsistent).
- Recommendation: correct both to `http://localhost:58080`. (Modifying EVO/TRYST requires their
  repos' authorization — not performed here.)

### ISSUE-5 — `/api/v1/sessions/{id}/changes` is repository-based (MEDIUM)
- A filesystem-only workspace (no registered repository) reports `{"repositories":[]}` even after
  real files were written. Change/diff reporting requires a repository (JGit diff). TRYST's adapter
  registers a repository, so its flow is unaffected, but the filesystem-only path cannot report
  changed files.

### ISSUE-6 — No end-to-end correlation chain (MEDIUM)
- The caller's task ID (TRYST) and the ENR/DeepSeek request/completion IDs are not propagated into
  the DCode session result. Only the DCode session ID crosses boundaries.

### ISSUE-7 — Plaintext API key in ENR state (HIGH, security)
- `~/.enr/state.json` stores the DeepSeek API key **in plaintext** (`"api_key": "sk-…"`). It should
  be encrypted/redacted at rest. (Value not reproduced here.)

### ISSUE-8 — Token usage not surfaced to the caller (LOW)
- DeepSeek token counts are captured at the ENR layer but not included in the DCode session result
  returned to the caller.

## Severity summary

| # | Issue | Severity | Status |
|---|---|---|---|
| 1 | DSML tool-call format | HIGH | FIXED |
| 2 | Stale git-tool test/prompt | LOW | FIXED |
| 3 | `llm.evo` nonexistent | BLOCKER (spec) | OPEN |
| 4 | Wrong adapter default endpoints | HIGH | OPEN (cross-repo) |
| 5 | Repository-based change reporting | MEDIUM | OPEN |
| 6 | No correlation chain | MEDIUM | OPEN |
| 7 | Plaintext ENR API key | HIGH (security) | OPEN |
| 8 | Token usage not surfaced | LOW | OPEN |

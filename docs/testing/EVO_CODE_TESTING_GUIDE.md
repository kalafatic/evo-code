# EVO-CODE — Testing Guide

Reproducible instructions for starting EVO-CODE in each mode, connecting to ENR (native and
Ollama-compatible protocols), selecting models, running the AI Commander workflow, terminal
functionality, Java scenarios and regression tests.

All commands assume Linux with JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (the machine
default Java is **8** — EVO-CODE must be started with `-vm`), the Maven wrapper `./mvnw` in the
repo root, ENR built in `/home/petr/git/enr-221126`, and Ollama on `127.0.0.1:11434`.

## 1. Build

```bash
cd /home/petr/git/evo-code-221126
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B verify                                   # full suite (hermetic)
./mvnw -B verify \
  -Ddcode.enr.endpoint=http://127.0.0.1:8111 \
  -Ddcode.live.model=smollm:135m                   # + live ENR/Ollama suites
```

Products (after build):

- Headless: `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64/dcode-headless`
- RCP: `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.ui.rcp/linux/gtk/x86_64/dcode`

## 2. Start ENR

```bash
cd /home/petr/git/enr-221126
./enr.sh serve -c enr.yaml        # foreground; or nohup ./enr.sh serve ... &
```

ENR surfaces:

- `http://127.0.0.1:8111/` — Web Control Center
- `http://127.0.0.1:8111/enr/v1/*` — native family surface (EVO-CODE `EnrProvider`)
- `http://127.0.0.1:8111/api` — Ollama-compatible API
- `http://127.0.0.1:8111/v1` — OpenAI-compatible API
- `http://127.0.0.1:8111/api/v1/models/*` — shared model management (EVO-CODE `ModelManagementClient`)

ENR native models `evo` / `tiny-evo` are protocol-test models (context 4 / 32); real agent work
uses Ollama models.

## 3. Headless mode (B1)

```bash
cd product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64
./dcode-headless -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java -nosplash -consoleLog
# daemonized:
nohup ./dcode-headless -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java -nosplash --launcher.suppressErrors &
```

- HTTP API: `http://127.0.0.1:58080/api/v1/*` (port from `dcode-config.json httpPort`, else
  `PortAllocator` from 58080; fallback range 58080–58580).
- The server persists its **actual bound endpoint** to `~/.dcode/server-endpoint.json` after a
  successful bind (used by the thin CLI to discover it).
- Health: `curl http://127.0.0.1:58080/api/v1/health`
- Graceful stop: `dcode-cli stop` (HTTP shutdown) or SIGTERM to the java process. Killing the
  launcher wrapper alone does **not** stop the JVM child.

`dcode-headless` is now the **server only** (daemon or interactive `dcode>` REPL). It no longer
accepts command arguments; a command argument prints a pointer to the thin CLI.

## 3b. Thin CLI (dcode-cli)

The thin CLI is a separate client process that talks to the running server over HTTP — it never
initializes a runtime.

```bash
cd product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.cli.client/linux/gtk/x86_64
./dcode-cli -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java status
./dcode-cli -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java runtimes
./dcode-cli -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java models
./dcode-cli -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java sessions
./dcode-cli -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java stop
```

- Endpoint discovery: `~/.dcode/server-endpoint.json` → `dcode-config.json httpPort` → default
  58080, each probed for a live, ready server.
- Unavailable server → clear message + non-zero exit (never starts a server).
- `start`: spawns `dcode-headless` (resolved from `-Ddcode.server.launcher=<path>`, else
  `dcode-headless` on PATH) with `-vm <java.home>/bin/java`, then polls for readiness.
- `stop`: `POST /api/v1/server/shutdown`, then polls until the endpoint is unreachable.
- `restart`: stop then start.

## 4. RCP mode (B3)

```bash
cd product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.ui.rcp/linux/gtk/x86_64
DISPLAY=:1 ./dcode -vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java -consoleLog
```

The RCP embeds the same server bundle; when the configured port is taken it binds the next free
port in the 58xxx block and publishes `dcode.server.port`. The UI connects to the local backend
automatically. Web lifecycle: `Tools ▸ Web Interface ▸ Start/Stop/Restart/Status/Open`.

## 5. Web server lifecycle (B4)

Config keys in `<dataDir>/dcode-config.json` (default `~/.dcode/dcode-config.json`):

- `webServer.enabled` — false disables the web/API server entirely (state DISABLED).
- `webServer.autoStart` — false leaves the server STOPPED at startup; start it via
  `server-start`, the RCP menu, or the CLI.

```bash
./dcode-headless ... server-status     # STOPPED / RUNNING / DISABLED / FAILED + URL
./dcode-headless ... server-start
./dcode-headless ... server-stop
./dcode-headless ... server-restart
```

Override the data dir with `-vmargs -Ddcode.home=<dir>` (after `-vmargs`, not a program arg).

## 6. Connecting to ENR and Ollama (Phase E/F)

Runtime registrations live in `~/.dcode/runtimes.json`.

- Native ENR: `POST /api/v1/runtimes` `{"type":"enr","endpoint":"http://127.0.0.1:8111"}`
- Ollama: `{"type":"ollama","endpoint":"http://127.0.0.1:11434"}`
- ENR via Ollama protocol: `{"type":"ollama","endpoint":"http://127.0.0.1:8111"}`

Model discovery:

```bash
curl http://127.0.0.1:58080/api/v1/runtimes/enr/models          # native ENR discovery
curl http://127.0.0.1:58080/api/v1/runtimes/ollama/models       # /api/tags
curl http://127.0.0.1:58080/api/v1/runtimes/enr-ollama/models   # /api/tags via ENR
```

Direct inference (non-streaming / streaming SSE):

```bash
curl -X POST http://127.0.0.1:58080/api/v1/inference/chat \
  -H 'Content-Type: application/json' \
  -d '{"runtimeId":"ollama","model":"qwen3.8-32k:latest","messages":[{"role":"user","content":"hello"}]}'

curl -N -X POST http://127.0.0.1:58080/api/v1/inference/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"runtimeId":"ollama","model":"qwen3.8-32k:latest","messages":[{"role":"user","content":"hi"}]}'
```

Stream frames: `event: start` → `data: {"token": ...}` frames → `event: done` (content,
finishReason, token counts) or `event: error`. Cancel with
`POST /api/v1/inference/cancel {"requestId": ...}`.

## 7. Ollama model management (F3)

Model management is delegated to ENR and surfaced through EVO-CODE's passthrough:

```bash
curl http://127.0.0.1:58080/api/v1/models/providers               # providers
curl http://127.0.0.1:58080/api/v1/models/ollama/installed       # installed models
curl http://127.0.0.1:58080/api/v1/models/jobs                    # shared download jobs
curl -X POST http://127.0.0.1:58080/api/v1/models/ollama/pull \
  -H 'Content-Type: application/json' -d '{"model":"smollm:135m"}'
```

Ollama runs as an independent systemd service (`systemctl status ollama`); EVO-CODE works with it
running independently and never installs or modifies it.

## 8. AI Commander workflow (Phase C/D/G)

1. Create a workspace: `POST /api/v1/workspaces {"name": "...", "rootPath": "/tmp/<ws>"}`
   (plain directory; no Git required).
2. Create a session:
   `POST /api/v1/sessions {"title", "workspaceId", "providerId": "ollama",
   "modelId": "qwen3.8-32k:latest"}`.
3. Start a task: `POST /api/v1/sessions/{id}/start {"task": "..."}` — runs async; watch
   `GET /api/v1/sessions/{id}` (`state`), `.../messages`, `.../toolcalls`, and SSE
   `GET /api/v1/events/stream?sessionId={id}`.

The agent protocol: the model must answer with exactly one JSON object per response —
`{"tool": "<name>", "arguments": {...}}` or `{"final": "<summary>"}`. The system prompt lists the
tool catalog with exact argument names. `COMPLETED`/`FAILED`/`CANCELLED` are terminal — create a
new session for a follow-up task.

## 9. Terminal and Java scenarios (Phase F/G)

Through the shell tool (`POST /api/v1/tools/shell/execute {"sessionId", "command"}`), execution is
confined to the workspace root, env-sanitized, 300 s default timeout (3600 s cap), 100 k output
cap.

Known environment facts (agents must be told): machine `java` on PATH is 8; use
`/usr/lib/jvm/java-21-openjdk-amd64/bin/{javac,java}` for Java scenarios.

Canonical Java scenario (G3, verified end-to-end):

> "Create a minimal Java application that prints EVO_CODE_ENR_INTEGRATION_OK. Compile it using the
> available JDK, execute it, capture the output and verify the exact expected result. Use the shell
> tool. Note: the machine default java is 8; JDK 21 is at /usr/lib/jvm/java-21-openjdk-amd64 if
> needed. Do not use git."

Verified model: `qwen3.8-32k:latest` (tools-capable). `smollm:135m` is small but does not follow
instructions reliably — do not use it for agent tasks.

## 10. Regression tests

```bash
./mvnw -B verify                                  # hermetic (208 tests)
./mvnw -B verify -Ddcode.enr.endpoint=http://127.0.0.1:8111 \
                 -Ddcode.live.model=smollm:135m   # + live ENR + Ollama
```

Regression coverage from this campaign:

- `harness/ToolEngineTest.searchCodeSkipsBinaryFiles` — `search_code` ignores binary files.

## 11. Troubleshooting

- `Unable to acquire application service` / launcher fails: wrong JDK — pass
  `-vm /usr/lib/jvm/java-21-openjdk-amd64/bin/java` (product requires JavaSE 17+).
- Port already bound: PortAllocator falls back within 58080–58580; check with
  `ss -tlnp | grep 5808` and use `dcode-headless stop`.
- JDT tools missing in headless: expected (no Eclipse resources workspace). In RCP they register
  after the workbench opens the workspace.
- `Invalid session transition COMPLETED -> RUNNING`: completed sessions are terminal by design;
  create a new session.
- Live tests skipped: ensure ENR/Ollama are reachable and pass the two `-D` properties; results
  are under `tests/eu.kalafatic.dcode.tests/target/surefire-reports/`.

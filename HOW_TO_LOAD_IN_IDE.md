# How to load Waryway Agent plugin into your IDE

**Current plugin version:** `0.1.30` (see `gradle.properties` `pluginVersion`). Includes Grok Build session recovery / failure UX beyond stream-only 0.1.27 builds.

Use this doc when you need a **working Local LLM agent** (plan + tools via server `/api/agent`), not chat-only.

---

## Known-bad builds (do not install)

| Artifact | Why bad |
|----------|---------|
| **`waryway-gab-plugin-0.1.23.zip` dated 2026-06-29** | Pre–AgentClient packaging. Jar has **no** `AgentClient` / `LocalLlmAgentSession`. Install from Disk of this zip → **chat-only** even if source tree has agent code. |
| Any zip / install stamped **before 2026-07-17** that was not rebuilt after agent-client sources landed | Same symptom: UI may look fine; Send never hits `/api/agent/*`. |

**Fix:** rebuild from current source (below) and install the **new** zip, or use `runIde` so the sandbox always loads live classes. Distinguish builds by **version ≥ 0.1.24** and zip file date **after** this rebuild.

---

## Path A — Dev sandbox (preferred for live agent)

Loads current sources into a disposable IDE. Best way to verify agent mode.

```bat
cd /d C:\dev\waryway-gab-plugin
.\gradlew.bat runIde
```

Or from the Gradle tool window: `waryway-gab-plugin` → Tasks → `intellij` → **`runIde`**.

1. Wait for the sandbox IDE to start.
2. Open the **right** tool window bar → **"Waryway Agent"**.
3. Provider should be **Local LLM** (default). Workbench shows **Agent mode** (default **ON**) and dry-run badge.
4. Start LocalLLM (Path C), then Send a goal.

**Gradle JVM:** if sync fails, Settings → Build, Execution, Deployment → Build Tools → Gradle → set **Gradle JVM** to bundled **Java 17** or **Java 21** (not JBR 25 for this project — see `gradle.properties` `org.gradle.java.home`).

---

## Path B — Zip install (host IDE)

Produces a disk installable plugin. **Always rebuild** after pulling agent-client sources; do not reuse an old `0.1.23` zip.

```bat
cd /d C:\dev\waryway-gab-plugin
.\gradlew.bat buildPlugin
```

1. Output zip (versioned):
   ```
   C:\dev\waryway-gab-plugin\build\distributions\waryway-gab-plugin-0.1.30.zip
   ```
   (exact name follows `pluginVersion` in `gradle.properties`.)
2. In **your** GoLand / IntelliJ (not only the sandbox):
   - **Settings → Plugins → ⚙ → Install Plugin from Disk…**
   - Select the **0.1.30** (or newer) zip.
3. Restart the IDE when prompted.
4. Confirm version under Settings → Plugins → Waryway Agent (should **not** still be a June 0.1.23 install).

### Sanity check (optional)

After `buildPlugin`, the lib jar inside the zip should list agent classes, e.g.:

```bat
jar tf build\libs\waryway-gab-plugin-0.1.30.jar | findstr /i "AgentClient LocalLlmAgentSession"
```

Expect paths like `com/waryway/gab/client/AgentClient.class` and `com/waryway/gab/chat/LocalLlmAgentSession.class`.

---

## Path C — LocalLLM server companion (required for agent)

Agent mode posts to the **stack** LocalLLM process on port **7400**, not to Gab/Grok cloud.

From the **stack** monorepo (not the plugin repo):

```bat
cd /d C:\Users\kawie\waryway\stack
scripts\localllm-run.bat
```

- Listens on **`http://127.0.0.1:7400`**.
- Pass **`-repo`** (or your script’s project-root flag) so agent tools resolve the workspace you care about.
- Keep this process running while using Local LLM agent mode.
- Health: open/root or health endpoints as documented for localllm; cold server → agent Start will fail with a reachable/offline style error (not silent success).

Default plugin base URL: `http://127.0.0.1:7400` (OpenAI facade under `/v1`; agent API on root `/api/agent/*`).

---

## Agent mode vs chat-only

| Mode | When | HTTP | Classes |
|------|------|------|---------|
| **Agent** (default for Local LLM) | Provider = Local LLM **and** Agent mode **ON** | `POST /api/agent/runs`, poll run status | `AgentClient` + `LocalLlmAgentSession` |
| **Chat** | Agent mode **OFF**, or Grok / Gab AI | OpenAI-style `…/v1/chat/completions` | `GabClient` + chat `AgentSession` |

### UI / settings

- **Workbench** (Local LLM only): checkbox **Agent mode** (default ON) and **Apply changes** (default off → dry-run).
- **Settings → Waryway Agent / Waryway Gab**:
  - `localLlmAgentMode` default **true**
  - `localLlmAgentDryRun` default **true** (safe: plan/preview only; no workspace writes until you opt into Apply)

If Agent mode is off, Send is free chat on `/v1/chat/completions` even with a fresh plugin build.

### Dry-run vs Apply

| Control | Effect |
|---------|--------|
| **Dry-run** (default) | Server receives `dryRun=true`. Tools plan/preview only; no writes under repo root. Badge shows **DRY-RUN**. |
| **Apply** | User explicitly enables Apply on the workbench (settings invert dry-run). Server may write under `repoRoot`. Use deliberately. |

---

## First-run / providers (brief)

1. Open **Waryway Agent** tool window.
2. **Local LLM** is the default provider (offline). Start Path C, model e.g. `localllm-coder`.
3. For cloud:
   - **Grok Build** (recommended if you use local Grok / GoLand AI Chat): run `grok login`, then select **Grok Build**. Uses `~/.grok/auth.json` and `https://cli-chat-proxy.grok.com/v1` — **not** console.x.ai team credits.
   - **Grok (API)**: paste a console.x.ai API key (prepaid team credits).
   - **Gab AI**: paste key from https://gab.ai → Settings → API Settings.
   Cloud chat uses tool-calling via the plugin; not `/api/agent`.

### GoLand MCP (optional; cloud / local MCP tools)

- Settings → Tools → MCP Server → Enable MCP Server.
- Plugin talks to `http://127.0.0.1:{port}/api/mcp/*`. Local LLM MCP tools are **off** by default (`localLlmUseMcpTools`); enable in Settings if needed. MCP is separate from server agent `/api/agent`.

### Base URLs

| Surface | URL shape |
|---------|-----------|
| Local OpenAI facade | `http://127.0.0.1:7400/v1` (`/chat/completions`, etc.) |
| Local agent API | `http://127.0.0.1:7400/api/agent/*` (root URL; trailing `/v1` stripped by client) |
| Grok Build | `https://cli-chat-proxy.grok.com/v1` (session from `~/.grok/auth.json`) |
| Grok (API) | `https://api.x.ai/v1` (PasswordSafe API key) |
| Gab AI | `https://gab.ai/v1` |

---

## Quick checklist (agent works end-to-end)

1. [ ] Plugin **≥ 0.1.24** via `runIde` **or** freshly built zip (not 0.1.23 from 2026-06-29).
2. [ ] `scripts\localllm-run.bat` running on **:7400** with correct `-repo`.
3. [ ] Provider **Local LLM**, **Agent mode ON**, dry-run OK for first try.
4. [ ] Send a goal → status shows agent planning (dry-run/APPLY), not only a plain chat completion.

Enjoy building with Waryway Agent.

# How to load Waryway Agent plugin into your IDE

**Current plugin version:** `0.1.46` (see `gradle.properties` `pluginVersion`). Includes Local LLM answer/thinking split + markdown headings, tool-window load/offline hardening, timeout/partial-reply recovery, skills in composer (slash/picker + discovery), message-first layout, collapsible shell/command output, Grok Build session identity + meta tool-use policy, and MCP/native tools when MCP is off.

Use this doc when you need a **working Local LLM agent** (plan + tools via server `/api/agent`), not chat-only.

**Org runbook (JB chat project):** stack `.grok/org/waryway-agent-jb-chat/artifacts/goland-jb-chat-runbook.md`  
**R2 agent runbook (cross-link):** stack `.grok/org/localllm-agent-ide-r2/artifacts/goland-agent-runbook-r2.md`

---

## Known-bad builds (do not install)

| Artifact | Why bad |
|----------|---------|
| **`waryway-gab-plugin-0.1.23.zip` dated 2026-06-29** | Pre–AgentClient packaging. Jar has **no** `AgentClient` / `LocalLlmAgentSession`. Install from Disk of this zip → **chat-only** even if source tree has agent code. |
| Any zip / install stamped **before 2026-07-17** that was not rebuilt after agent-client sources landed | Same symptom: UI may look fine; Send never hits `/api/agent/*`. |

**Fix:** rebuild from current source (below) and install the **new** zip, or use `runIde` so the sandbox always loads live classes. Distinguish builds by **version ≥ 0.1.24** (skills/layout/anti-thrash: **≥ 0.1.42**, current: **0.1.46**) and zip file date **after** this rebuild.

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

**“Switch to native launcher (`goland64.exe`)” note:** harmless for `runIde`. The sandbox starts GoLand via Gradle **JavaExec** (plugin classpath injected), not `bin\goland.bat` / `bin\goland64.exe`. `build.gradle.kts` sets `-Dide.native.launcher=false` so that tip stays muted. For day-to-day GoLand (non-sandbox), prefer the native `goland64.exe` / Toolbox app.

---

## Path B — Zip install (host IDE)

Produces a disk installable plugin. **Always rebuild** after pulling agent-client sources; do not reuse an old `0.1.23` zip.

```bat
cd /d C:\dev\waryway-gab-plugin
.\gradlew.bat buildPlugin
```

1. Output zip (versioned):
   ```
   C:\dev\waryway-gab-plugin\build\distributions\waryway-gab-plugin-0.1.46.zip
   ```
   (exact name follows `pluginVersion` in `gradle.properties`.)
2. In **your** GoLand / IntelliJ (not only the sandbox):
   - **Settings → Plugins → ⚙ → Install Plugin from Disk…**
   - Select the **0.1.46** (or newer) zip.
3. Restart the IDE when prompted.
4. Confirm version under Settings → Plugins → Waryway Agent (should **not** still be a June 0.1.23 install).

### Sanity check (optional)

After `buildPlugin`, the lib jar inside the zip should list agent + skill + layout classes, e.g.:

```bat
jar tf build\libs\waryway-gab-plugin-0.1.46.jar | findstr /i "AgentClient LocalLlmAgentSession SkillCatalog SkillDiscovery ComposerSkill ChromeLayout ComposerLayout AgentSystemPrompt"
```

Expect paths like `com/waryway/gab/client/AgentClient.class`, `com/waryway/gab/chat/LocalLlmAgentSession.class`, and skill/layout helpers under `com/waryway/gab/skills` / `com/waryway/gab/ui`.

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

## Skills in prompt (0.1.42+)

Skill context can be injected into outbound goals from the composer (slash autocomplete / picker). No auto-send on selection.

### Sources

| Source | Location |
|--------|----------|
| **Bundled** | `SkillRegistry` / guided skills via `SkillCatalog` |
| **User** | `~/.grok/skills/*/SKILL.md` (`%USERPROFILE%\.grok\skills` on Windows) |
| **Project** | `{project.basePath}/.grok/skills/*/SKILL.md` |

Merge precedence (same id): **project > user > bundled**.

### Composer

1. Focus the composer and type **`/`** for slash autocomplete / skill picker.
2. Select a skill → plugin injects `[skill:<id>]` + body into the outbound goal text.
3. Selection does **not** auto-send; review and press **Send** (or Enter).
4. Both **agent** (`/api/agent`) and **chat** send paths receive skill context via the skill send injection path.

Detail notes (stack org):  
`.grok/org/waryway-agent-jb-chat/artifacts/skill-discovery-notes.md`

---

## Layout — message-first (0.1.42+)

Tool window prioritizes the **chat transcript**. Residual vertical height goes to the message list; chrome starts collapsed where possible.

| Surface | Default |
|---------|---------|
| **Chat history** | **CENTER** — residual height; grows with the window |
| **Activity log** | **Collapsed** by default |
| **Workbench Advanced** | **Collapsed** by default (primary status/badges stay visible) |
| **Secondary north chrome** (thinking/skill combo) | Behind **gear**; default hidden |
| **Command / shell output** (timeline blocks) | **Collapsed** by default |
| **Composer** | Compact, grow-on-demand (~1–6 visible rows), soft-wrap estimate |

**Composer chrome:** prompt textarea is **full width**; Stop/Send/badge **action strip sits under** the textarea (not EAST-of-prompt), so narrow tool windows keep a usable prompt column.

**Dual-path chrome tax (idle):**

| Path | Extra north tax at idle |
|------|-------------------------|
| **Grok Build** | none beyond north primary (no workbench) |
| **Local LLM** | workbench **primary row** only; Advanced collapsed |

**Width notes:** ~320–480px narrow (composer/action strip tight; prompt stays full-width above actions); ~600px+ comfortable for transcript + chrome.

Layout policy is locked in pure helpers / unit tests (not a full live IDE GUI matrix):  
`ChromeLayoutDefaults`, `ComposerLayoutMetrics` under `src/main/kotlin/com/waryway/gab/ui/` with matching `*Test` classes.

Detail notes (stack org):  
`.grok/org/waryway-agent-jb-chat/artifacts/layout-maximize-notes.md`

---

## Anti-thrash notes (0.1.42+)

| Path | Behavior |
|------|----------|
| **Local LLM agent** (`HeuristicPlan` on stack) | Simple how-to / commit / localllm / skill-style goals prefer known anchors (`AGENTS.md`, scripts, `apps/localllm`) over multi-keyword grep thrash |
| **Cloud Grok Build** | `AgentSystemPrompt` anti-thrash policy for meta/how-to goals (session facts; avoid thrash tool loops) |

Unit/Bazel coverage exists for heuristics and `AgentSystemPrompt`. **Do not** claim a full live GUI E2E thrash matrix unless it was run and logged.

Verification log (stack org):  
`.grok/org/waryway-agent-jb-chat/artifacts/e2e-jb-chat-verification-log.md`

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

**v0.1.33+ Grok Build identity / session context:** questions like “what model?” / which provider / tools backend are answered from **session facts** (selected model id, provider label, tools backend) injected into the system prompt — not by searching the project for the word “model.” Workspace coding still uses **MCP or native IDE tools** (intentional difference from Grok Build CLI platform tools, skills, and subagents).

### GoLand MCP (optional; native tools always available)

**v0.1.32+:** cloud agent tools stay **on** even when MCP HTTP is off. The plugin prefers JetBrains MCP Server when reachable; otherwise it runs **native in-process** tools (read/search/edit/`execute_terminal_command`/`build_project`). Enable MCP only if you want the full IDE toolset (PSI, apply_patch, DB tools, etc.).

Local LLM **server** agent mode (`/api/agent`) does **not** need MCP. Local LLM **chat** tools stay off by default (`localLlmUseMcpTools`).

1. **Optional:** **Settings → Tools → MCP Server → Enable MCP Server** in the **same** IDE process that hosts the plugin.
2. Plugin discovers `http://127.0.0.1:{port}/api/mcp/list_tools`:
   - **IDE MCP Port** in Settings → Tools → Waryway Gab (`0` = auto)
   - else `IDE_PORT` env
   - else scan **63342–63352** (`GET …/list_tools` must return 2xx + body)
3. **Common false “offline”:** port answers `/api/about` but `list_tools` is **HTTP 404** → MCP Server not enabled. Tools badge shows **Tools · native**; session log: `mcp=ide_up_mcp_off backend=native`.
4. **Discovery is cached process-wide** (v0.1.31+): hits reused; misses ~**30s**; click the **Tools** badge to force re-probe.
5. Session log: `mcp=available|ide_up_mcp_off|unreachable tools=on|off backend=mcp|native|off`.

**If you want full MCP:** enable MCP Server, pin **IDE MCP Port**, click the Tools badge, then Send again.

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

1. [ ] Plugin **≥ 0.1.46** via `runIde` **or** freshly built zip (not 0.1.23 from 2026-06-29).
2. [ ] `scripts\localllm-run.bat` running on **:7400** with correct `-repo`.
3. [ ] Provider **Local LLM**, **Agent mode ON**, dry-run OK for first try.
4. [ ] Optional: type `/` in composer → pick a skill → Send (skill body in goal; no auto-send on pick).
5. [ ] Chat history is center-dominant; activity log / workbench Advanced start collapsed.
6. [ ] Send a goal → status shows agent planning (dry-run/APPLY), not only a plain chat completion.

Enjoy building with Waryway Agent.

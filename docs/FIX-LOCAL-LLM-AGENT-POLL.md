# Fix prompt: Local LLM agent poll spam + timeouts (Gab plugin)

Use this if you only have the plugin repo open and LocalLLM server changes are already landed (or as a standalone recap).

## Problem (observed)

Fail package `fail_*_manual_export.md` / `agent_run_failed`:

- Provider **Local LLM**, path **Agent · APPLY**
- Run stuck `state=planning step=0/30` for ~480s
- Activity log flooded with `GET …/api/agent/runs/{id}` every ~0.75s
- Client timeout: `Agent run timed out after 480s`

Root cause is dual:

1. **Server (stack `apps/localllm`)**: pure-Go `go-cpu` Generate for planner MaxTokens≥512 is extremely slow; cancel did not stop Generate (`context.Background()`).
2. **Plugin**: 8-minute hard timeout + log-every-poll made the UI look like “too many communications with local llm”.

## Already fixed in stack (localllm)

- Plan MaxTokens 128; plan Generate timeout 90s → `HeuristicPlan(goal)` fallback
- Compact tool catalog in planner prompt
- Cancelable run context (`Loop.Cancel` cancels Generate)
- Run `message` field for progress

## Plugin changes (this repo, v0.1.36+)

### Required behavior

1. **`AgentClient.getRun(id, quiet=true)`** during poll loop — do **not** log every GET.
2. Log only on **state / step / message** change (`agent progress: …`).
3. **Heartbeat** every ~15s: `… still planning (Ns elapsed)…` without extra HTTP log spam.
4. **Adaptive poll interval**: start ~1.5s, backoff to max 5s while state unchanged.
5. **Default timeout 30 minutes** (was 8). Setting: `localLlmAgentTimeoutMinutes` (5–120).
6. Parse and surface server `message` on `AgentRun` and in `formatStatusLine`.
7. Wire timeout from settings in `WarywayGabToolWindowPanel` when constructing `LocalLlmAgentSession`.

### Files

- `src/main/kotlin/com/waryway/gab/client/AgentClient.kt`
- `src/main/kotlin/com/waryway/gab/chat/LocalLlmAgentSession.kt`
- `src/main/kotlin/com/waryway/gab/settings/WarywayGabSettings.kt`
- `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt`
- tests under `src/test/kotlin/…/LocalLlmAgentSessionTest.kt`, `LocalLlmSendUxTest.kt`
- `gradle.properties` → bump version

### Acceptance

- Unit tests green: `LocalLlmAgentSessionTest`, `AgentClientTest`, `LocalLlmSendUxTest`
- Against live `:7400` with go-cpu: Agent run either finishes via heuristic plan ≤ ~2 min or shows heartbeats (not hundreds of identical SYS lines)
- Stop/cancel still clears `activeRunId` and posts cancel

### UX guidance for users

- **Q&A** (“what is going on?”) → **Chat** mode, not Agent
- **Agent** → concrete tool goals with paths (`Read pkg/…/foo.go and …`)
- For speed: `inferenceBackend: "cpp"` or go-vulkan if available; pure Go is correct but slow on CPU

## One-shot implement prompt (copy-paste)

```
In C:\dev\waryway-gab-plugin, fix Local LLM agent poll UX for slow pure-Go go-cpu:

1. AgentClient.getRun: add quiet: Boolean = false; when quiet, skip HTTP/SYS log per poll.
   Parse optional JSON field "message" into AgentRun.message.
2. LocalLlmAgentSession poll loop:
   - getRun(id, quiet=true)
   - log only on state/step/message change
   - heartbeat every 15s with elapsed time
   - backoff poll interval from 1.5s to 5s while stuck
   - DEFAULT_TIMEOUT_MS = 30 minutes
   - formatStatusLine appends " — {message}" when present
3. WarywayGabSettings: localLlmAgentTimeoutMinutes (default 30, clamp 5–120)
4. WarywayGabToolWindowPanel: pass timeoutMs = settings.localLlmAgentTimeoutMinutes * 60_000
5. Bump pluginVersion; update tests (FakeAgentOps getRun signature).
6. Run: gradlew test --tests LocalLlmAgentSessionTest --tests AgentClientTest
```

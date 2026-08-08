## [Unreleased]

## [0.1.46]

### Fixed
- **Local LLM agent final bubble mixed plan/meta into the answer:** results now split **Answer** (main bubble) from **Thinking / plan** (collapsible above the answer). Multi-file `read_file` dumps are condensed to path + short excerpts so AGENTS/README walls no longer dominate the reply.
- **Answer headings were flat plain text:** lightweight markdown rendering for `###` headings and `**bold**` in chat bubbles.

### Changed
- Final display hierarchy is **Answer → Thinking (goal/plan/tasks) → footer**. History/copy still gets the full combined text.

## [0.1.45]

### Fixed
- **Tool window / composer not loading (especially Local LLM offline):** programmatic provider combo selection no longer re-enters `rebuildUI` mid-build. Nested rebuild orphaned `inputScroll` and could hide the prompt; chrome listeners are wired only after selection is stable.
- **Offline model/status errors showed `null`:** use exception class name when message is blank; keep catalog fallbacks; Local LLM workbench stays visible with Offline status.
- **GET listModels/status hangs:** request timeouts on GabClient GETs and shorter LocalLLMService connect/request budgets so offline open stays snappy.
- **Composer SOUTH preferred height:** BorderLayout ignores `minimumSize` for N/S — also pin `preferredSize` on the composer shell.
- **Tool window construction failures:** factory catches and shows a recovery panel instead of a blank tool window.

## [0.1.42]

JB chat skills + layout maximize + anti-thrash (cross-stack with agent prompt).

### Added
- **Skills in agent prompt / composer:** skill discovery + catalog (`SkillCatalog`, `SkillDiscovery`, `SkillRef`); composer skill picker and slash injection (`ComposerSkillPicker`, skill send path).
- **Layout maximize helpers:** chrome/composer layout defaults so the chat surface prioritizes transcript over secondary chrome.

### Changed
- **Anti-thrash prompt/heuristics:** agent system prompt and related client/session path discourage thrash loops; packages skill + layout classes in the release zip.

## [0.1.40]

Readable Local LLM agent result display — progress summaries, hierarchical final bubble, collapsible tool outputs.

### Fixed
- **Progress lines jammed multi-line `tool_result` into one space-joined jumble:** multi-line tool results are summarized on progress (no full tree dump in status lines).
- **Final assistant reply was a flat wall of text:** hierarchical layout with status/goal/plan, an Answer section, capped task evidence, and footer badges (dry-run/apply, repoRoot, tool count).

### Changed
- **Large tool results** use the collapsible command-output path (`onCommandOutput` → chat timeline) so full trees expand-to-view instead of bloating the main bubble.

## [0.1.39]

Timeout handling — keep partial work, smarter retries, session budgets, soft warnings.

### Fixed
- **Mid-stream timeouts wiped the reply:** streamed tokens are now attached as `partialContent` and shown above recovery text (`— Timed out (partial reply kept) —`).
- **Timeout retries re-POSTed after useful partials:** empty hangs still retry; once ≥24 chars streamed, timeout surfaces immediately (no double spend / concatenated streams).
- **Retry streams concatenated into one bubble:** `onStreamReset` clears the live body before each retry attempt.

### Added
- **Multi-turn agent session budget** (≈4× stream timeout, floor 30m / cap 4h) so tool loops cannot run unbounded; soft warn at 80%.
- **Local agent soft warnings** at 80% of absolute and stall budgets; heartbeats show remaining abs/stall time.
- `GabClient.shouldRetryTimeout` / `resolveSessionTimeoutMs` pure policies + unit tests.

### Changed
- AgentSession catches stream timeouts and returns a graceful terminal Result (partial kept) instead of always throwing to the outer catch.
- Fail packages auto-export on timeout terminals (`agent_timeout` trigger).

## [0.1.38]

Better timeout handling for Local LLM, Grok Build, Grok API, and Gab AI agents.

### Fixed
- **Premature chat stream timeouts:** SSE request budget was hard-coded at **210s** for all providers. Defaults are now **15 minutes** (cloud) and **30 minutes** (Local LLM chat). Long reasoning / slow go-cpu generation no longer die mid-stream as often.
- **Opaque timeout errors:** `HttpTimeoutException` and agent poll timeouts map to recovery-oriented copy (raise timeout, retry, Chat vs Agent) instead of bare exception text.
- **No retry on stream timeouts:** transient timeouts / connection resets are retried like 502/504 (up to 3 attempts with backoff).

### Added
- **Settings → Chat stream timeout (minutes, 0 = auto)** for Gab / Grok / Grok Build / Local chat.
- **Local agent stall detection:** no state/step/message/event progress for ~40% of the poll budget (min 10m, capped by absolute) cancels with a clear stall message; progress resets the stall clock so long progressive runs still get the full agent poll timeout.
- Shared `AgentTimeoutUx` classification used by Local / Grok Build / Gab failure formatters.

### Changed
- Local agent timeout messages distinguish **stall** vs **absolute** budget exhaustion.

## [0.1.37]

Local LLM agent poll UX — stop flooding Activity log during slow pure-Go planning.

### Fixed
- **Agent poll spam:** poll loop uses quiet `getRun` (no per-GET HTTP/SYS lines). Logs only on state/step/message change (`agent progress: …`) plus a ~15s heartbeat while stuck.
- **8-minute hard timeout:** default client timeout is **30 minutes** (was 480s). Setting: *Agent poll timeout (minutes)* under Local LLM (5–120).
- **Adaptive poll interval:** starts ~1.5s, backs off to 5s while state is unchanged (long go-cpu plan Generate).
- Surfaces optional server `message` on status lines when present.

### Added
- Settings field **Agent poll timeout (minutes)** (default 30).

## [Unreleased notes / prior]

### Added
- **Collapsible bash/command output in chat:** `execute_terminal_command` / `build_project` results appear in the agent turn as expandable blocks (collapsed by default, Show/Hide + copy). Full output still goes to the model and Activity log.
- **Stop that actually unblocks:** Stop closes the live SSE body (and interrupts the worker thread) so cancel is not stuck waiting on the next chunk; Stop is also next to Send.
- **Fail package export:** Activity log → **Export fail** writes a Markdown package (trigger, model/provider, last Q/A, full activity log, paths) under `.waryway-gab/logs/`. Path is shown in chat and copied to the clipboard.
- **Durable session log file:** every session appends to a file under `.waryway-gab/logs/session_*.log`; path is shown under the Activity log (copy-path button).
- Auto fail package on user Stop, request/agent failures, max-iterations / stuck terminals.

### Fixed
- **Chat dump loops:** status lines in the agent bubble soft-capped (full detail stays in Activity log / fail package); ERR lines mirrored to chat capped per turn.
- **0.1.28 Multi-turn chat:** Stopped re-persisting the user message after each agent turn (system prompt prepend shifted indices → duplicate “You” bubbles).
- **0.1.28 Tab bar:** Programmatic history/title refresh no longer fires conversation switch / reloads the message list mid-turn.
- **0.1.28 Send:** Atomic in-flight guard so Enter cannot start two concurrent turns.
- **0.1.28 Display:** Conversation reload maps only user + final assistant text (hides tool-call internals).
- **0.1.27 Grok Build stream fix:** Snapshot-safe SSE merge via `StreamContentMerger` — stops cumulative chunks from quadratic-append RAM death.
- Stream UI coalescer (~40ms) + scroll throttle + soft cap to keep the chat pane responsive under high-frequency deltas.
- `AgentSession` resets stream body each completion iteration so multi-step agent turns do not accumulate stale stream state.

### Changed
- **0.1.24** packaging stamp: rebuild after AgentClient / LocalLlmAgentSession sources so Install-from-Disk is not stuck on chat-only.
- Document known-bad **0.1.23 (2026-06-29)** zip; prefer `runIde` or fresh `buildPlugin` zip. See `HOW_TO_LOAD_IN_IDE.md`.

## [0.1.34]

Collapsible shell command output in the agent chat timeline. Prefer Install-from-Disk of **0.1.34+** or `runIde` after rebuild.

### Added
- **Command output in plugin view:** after `▸ cmd: …`, full stdout/stderr is shown as a collapsed expandable panel (exit code + line count in the header). Expand with **Show**; copy output without expanding via the copy icon.

## [0.1.33]

Grok Build CLI parity — session identity + meta tool-use policy (Strategy A + light C). Prefer Install-from-Disk of **0.1.33+** or `runIde` after rebuild.

### Added
- **Session facts in system prompt (Grok Build / identity-aware cloud):** selected model id, provider display label, and tools backend (`mcp` | `native` | `off`) are injected so the agent can answer “what model / provider / session?” from live session context.
- **Meta / identity tool-use policy:** questions about model, provider, session, or tools backend are answered from session facts — not by defaulting to project search (`search_in_files_by_text("model")`) or exploratory shell for identity discovery. Coding tasks still use IDE tools (MCP preferred, native fallback when MCP off) as in 0.1.32.

### Docs / intentional difference
- Plugin tools remain **JetBrains MCP / native IDE tools** (feature). This is **intentional** vs Grok Build CLI platform tools, skills, and subagents; the agent loop stays plugin `AgentSession` + OpenAI tool_calls. Plan: `.grok/org/plugin-grok-build-parity/plan.md`.

## [0.1.32]

MCP reliability + login browser + command flow when MCP HTTP is off.

### Fixed
- **Tools stuck off when MCP 404:** GoLand often answers `/api/about` while `/api/mcp/list_tools` is HTTP 404 (MCP Server not enabled). Agent no longer runs with `tools=off` — **native in-process tools** handle read/search/edit/`execute_terminal_command`/`build_project`.
- **Login web pages not shown:** Grok Build **Login…** button opens `https://x.ai/cli` and best-effort starts `grok login` so browser OAuth appears; coaching panel has the same actions + working hyperlinks.
- **Chat URLs dead text:** message bubbles linkify `http(s)://…` and open via `BrowserUtil`.
- **MCP diagnostics:** probe distinguishes `AVAILABLE` / `IDE_UP_MCP_OFF` / `UNREACHABLE`; Tools badge + session log show backend (`mcp` vs `native`).
- **Command status lines:** terminal tools show `▸ cmd: …` so shell commands are visible in the agent bubble.

### Added
- `NativeIdeToolExecutor` core tool fallback.
- Tools badge (click re-probes MCP) and Grok **Login…** next to Send.

## [0.1.31]

Grok Build connection reliability + Local LLM parity. Prefer Install-from-Disk of **0.1.31+** or `runIde` after rebuild.

### Fixed
- **MCP discovery re-scan thrash:** process-wide endpoint cache so each agent Send / tool call does not re-probe ports 63342–63352. Positive hits are reused; misses are negative-cached ~30s; transport failures invalidate and re-discover once. Session log records `mcpEndpoint=…` when on.
- **Grok Build / cloud system prompt vs tools:** when MCP is off, system prompt no longer claims IDE tools (avoids hallucinated tool_calls with `tools=false`).
- **Stop/Send race (Grok Build + Local LLM):** Stop no longer clears `sendInFlight` early — next Send waits until the worker finishes cancel.
- **Model combo after provider switch:** reseed with provider fallbacks on rebuild / failed `listModels` so Localllm IDs do not stick under Grok Build.
- **Local LLM root URL:** `…/v1/` now normalizes to host root for agent + health (was left as `…/v1` and 404’d).
- **Local agent Stop:** `InterruptedException` during poll sleep maps to clean “Stopped by user”.
- **Grok Build badge:** shows PasswordSafe override when live session is missing/expired.

### Docs
- `HOW_TO_LOAD_IN_IDE.md` / `README.md` — MCP discovery cache + version stamp.

## [0.1.30]

Grok Build interaction polish (beyond stream-only 0.1.27 builds). Prefer Install-from-Disk of **0.1.30+** or `runIde` after rebuild.

### Added / Improved
- **Grok Build session recovery:** detect missing or expired coaching and refresh the session without an IDE restart.
- **Actionable chat/send failures:** map auth and network failures to clear operator-facing messages instead of bare HTTP text.
- **Tool-window failure UX:** clearer Grok Build error presentation and a session status chip in the tool window.
- **Local LLM `/api/agent` terminal fidelity:** cancel, timeout, and empty `finalAnswer` paths behave consistently at the agent boundary.

### Fixed / Guarded
- **Stream merge contracts:** regression coverage keeps snapshot-safe SSE merge (`StreamContentMerger`) locked so cumulative chunks do not reintroduce quadratic-append / UI thrash.

## [0.1.0]

### Added
- Initial release of Waryway Gab plugin for GoLand / IntelliJ.
- Gab AI chat tool window.
- Settings for API key.
- Basic client for Gab API.

[0.1.0]: https://github.com/waryway/waryway-gab-plugin/releases/tag/v0.1.0

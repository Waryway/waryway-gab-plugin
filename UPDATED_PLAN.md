# Waryway Gab - Updated Implementation Plan

**Date:** 2026-06-26 (post initial scaffolding)  
**Status:** Plugin is now **loadable and runnable in GoLand**.  
**Previous plan:** See [PLAN.md](./PLAN.md)

---

## Current State Summary

We have implemented the foundation so the plugin can be opened and run inside the IDE:

### What Works Now
- Full Gradle + `org.jetbrains.intellij.platform` 2.x project skeleton
- Right-side `ToolWindow` ("Waryway Gab")
- **Coaching / Onboarding UI** (shown when no API key):
  - Step-by-step instructions
  - Direct link to gab.ai
  - Paste API key field + "Test Key" + "Open Settings"
- Settings page (`Settings > Tools > Waryway Gab`)
  - API key stored securely via `PasswordSafe`
  - Test connection button
- Dynamic model loading from `GET /v1/models` (refresh button in chat header)
- Basic chat UI (model, thinking, skill dropdowns + input + send/stop)
- **Real Gab AI calls**:
  - Non-streaming `chat/completions`
  - Live usage (prompt/completion tokens + credits) printed after responses
- Core domain models: `ChatMessage`, `ToolCall`, `Usage`, `ContextAttachment`, `Skill`, `Conversation`
- Minimal `GabClient` (java.net.http + coroutines)

### Rough Edges / Tech Debt (to address soon)
- Chat is a single `JTextArea` (no proper message list, no markdown, no per-message usage)
- No conversation history persistence
- `runBlocking` + background `Thread` in UI code (works for MVP but not scalable)
- No real SSE streaming
- No agent loop (tool_calls not handled)
- No workspace context injection
- Token/credit/context meters are not prominent or accurate
- No skills system
- No file edit capabilities

---

## Architecture Recap

- **Path A (chosen)**: Native JetBrains plugin (Kotlin, runs inside the IDE process)
- Uses IntelliJ Platform APIs directly for context, editing, PSI, etc.
- `mcps/goland1/` JSON files are **reference only** (great inspiration for tool surface)
- Gab AI: OpenAI-compatible at `https://gab.ai/v1`
  - Streaming via SSE (`stream: true`)
  - Full tool calling support (`tools` + `tool_calls`)
  - Credit-based pricing + rate limit headers

The plugin must feel lightweight to the user while running a full multi-turn **agentic loop** behind the scenes.

**Critical non-negotiable:** Token usage, credit usage, and context size must be **first-class and always visible**.

---

## Completed vs Remaining (from original PLAN)

| Phase | Original Goal | Status |
|-------|---------------|--------|
| 0 | Setup, decisions, skeleton, Gab spike | ✅ Done |
| 1 | Tool window + settings + coaching UI | ✅ Mostly done |
| 2 | Streaming + core agentic loop | 🔶 Partial (client exists, no SSE, no loop) |
| 3 | Workspace context | ❌ Not started |
| 4 | Skills system | ❌ Not started |
| 5 | Code modification & agent edits | ❌ Not started |
| 6 | Polish, safety, persistence | ❌ Not started |
| 7 | Packaging / docs / testing | 🔶 Basic docs + loadable |

---

## Updated Phased Roadmap (Post-Scaffolding)

### Phase 2a: Streaming Foundation (Next Immediate Piece)
Goal: Replace fake/simulated responses with real streamed output + prepare for tool calls.

**Must deliver:**
- Proper SSE streaming client for `/v1/chat/completions?stream=true`
- Parse both `delta.content` and partial `delta.tool_calls`
- Live appending to chat UI while streaming
- Cancellation (`Stop` button aborts the HTTP call cleanly)
- Accumulate usage from the final chunk (or `x_usage` style fields)
- Basic live status: "Streaming...", "Thinking..."

**Key files to touch/create:**
- `src/main/kotlin/com/waryway/gab/client/GabStreamingClient.kt` (or refactor `GabClient`)
- Improve `WarywayGabToolWindowPanel.kt` (replace JTextArea with a proper message list component)
- Add a `ConversationService` or `ChatSession` that owns history + current stream

### Phase 2b: Agent Loop Core
Goal: When the model returns `tool_calls`, the plugin executes them and continues the conversation automatically.

**Core loop:**
1. Send messages + tools
2. If `finish_reason == "tool_calls"`:
   - Show status "Using tool: read_file..."
   - Execute tool(s) using IDE services
   - Append `role: "tool"` messages with results
   - Re-send and loop until `stop` or normal completion
3. All tool results contribute to token/credit accounting

**First tools to implement** (inspired by `mcps/goland1`):
- `read_file` (path → content)
- `list_directory` (or project file summary)
- `search_text` / `grep`
- `get_current_file_and_selection`
- `get_problems` (compiler errors/warnings for open files)
- `apply_patch` or `replace_text_in_file` (safe, later)

### Phase 3: Workspace Context (High Impact)
- Automatic context: open editors + selected text + current caret file
- Explicit attachments (drag & drop, @-mentions, "Attach file")
- Smart truncation + token budget estimation before sending
- Context chips UI (removable)
- Optional deeper context (git diff, recent problems, key files)

### Phase 4: Skills System
- Skill definition (JSON + loader from `~/.waryway-gab/skills/` + project `.waryway/skills/`)
- Skills dropdown + search
- Dynamic form based on `parameters`
- Skill selection injects system/user prompt + enables specific tools

### Phase 5: Safe Code Edits
- Tools that can modify the project:
  - `replace_text`
  - `apply_unified_diff`
  - `create_file`
- UX: For larger changes → show diff/preview + "Apply" / "Reject"
- Use `WriteCommandAction`, `Document`, undo support
- Record all AI-driven changes (for audit + VCS)

### Phase 6: Polish & Reliability
- Proper conversation list + history persistence (project or global)
- Better chat rendering (JEditorPane / markdown / syntax highlighted code blocks)
- Prominent live usage panel (session totals, last turn, context %)
- Rate limit / credit exhaustion handling with clear messages
- Stop during tool execution
- Confirmation for risky edits

---

## Immediate Next Piece: "Streaming + Basic Agent Loop"

This is the recommended piece to build right after loading the plugin.

### Goals for This Next Piece
1. Real SSE streaming from Gab AI.
2. Clean separation of streaming client from UI.
3. A `Conversation` / `ChatSession` model that maintains full message history + cumulative usage.
4. First working agent loop with 1–2 read-only tools (e.g. `read_file`, `get_open_files`).
5. UI updates:
   - Show streaming text live
   - Show "Agent is using tool: X..." status
   - Prominent usage bar (tokens + credits)
6. Cancellation works for both streaming and tool execution.

### Suggested Technical Tasks

#### 1. Streaming Client
- Create or heavily refactor `GabClient` to support streaming.
- Recommended approach: Add Ktor client (see commented deps in `build.gradle.kts`) **or** implement SSE parsing on top of `java.net.http.HttpClient` + `Flow` / `HttpResponse.BodyHandlers`.
- Emit events: `ContentDelta`, `ToolCallDelta`, `UsageUpdate`, `StreamFinished`, `Error`.
- Support cancellation via `CompletableFuture` / `Job` / `HttpRequest` abort.

#### 2. Conversation Management
Create `src/main/kotlin/com/waryway/gab/chat/ConversationManager.kt` (or similar):
- Owns `Conversation` (list of `ChatMessage`)
- Tracks cumulative `Usage`
- Estimates current context size vs model limit (simple heuristic or call `/count_tokens` equivalent if available)
- Exposes `send(userText: String, tools: List<ToolDef>)` that runs the full agent loop

#### 3. Tool Registry + Execution
Create:
- `ToolDefinition` (name, description, JSON schema parameters)
- `ToolExecutor` interface + implementations using `Project`, `PsiManager`, `FileDocumentManager`, etc.
- Start with:
  - `read_file`
  - `search_in_project` (text search)
  - `get_open_editors_and_selections`

Tool results should be returned as `role: "tool"` messages with JSON or text content.

#### 4. UI Improvements (incremental)
- Replace or augment the single `chatArea` JTextArea with a list of messages (user vs assistant bubbles).
- Add a small status line or badge: "Streaming…", "Calling read_file…", "Thinking (reasoning model)".
- Add a visible usage panel (top or bottom of chat):
  - Current turn: prompt / completion / credits
  - Session total
  - Context used / model limit (e.g. "14.2k / 128k (11%)")

#### 5. Stop / Cancellation
- `Stop` button must cancel the current `HttpRequest` / coroutine scope.
- If in the middle of tool execution, attempt to interrupt gracefully.

### Non-Goals for the *Immediate* Next Piece
- Full skills form rendering
- Write/edit tools (save for Phase 5)
- Drag & drop
- History persistence across IDE restarts

---

## Token, Credit & Context Visibility (Re-emphasized)

Per the original requirements, these must be **prominent**:

- Before every request: estimate total tokens (history + context + tools) and warn if high.
- During/after response: show the `usage` object returned by Gab AI.
- Always visible: session totals, credits used this turn, context budget meter.
- Per-message hover/expand showing contribution to cost.

The UI should never hide these numbers.

---

## Tool Surface Ideas (Reference: mcps/goland1)

High-value tools the agent should eventually have access to:

Read-only:
- `read_file`
- `search_text` / `search_regex`
- `list_directory`
- `get_open_files`
- `get_current_selection`
- `get_problems` (errors/warnings)
- `get_symbol_info`

Write (later, with safety):
- `replace_text_in_range`
- `apply_patch`
- `create_file`
- `rename_refactoring` (careful)

Build/Run:
- `build_project`
- `run_configuration`

These map very closely to the existing JSON schemas in `mcps/goland1/tools/`.

---

## Open Decisions / Questions

1. **HTTP Client for streaming**: Ktor vs pure java.net.http + custom SSE parser? (Ktor is recommended for cleanliness.)
2. **JSON**: Currently using naive regex parsing in `GabClient`. We should introduce `kotlinx.serialization` or Jackson for robustness (especially tool arguments).
3. **Context budget strategy**: Hard cap? User-configurable? Auto-summarize old messages?
4. **Edit safety default**: Preview + confirm for all edits, or auto-apply small trusted ones?
5. **Multiple conversations**: Support tabs or a conversation sidebar soon?
6. **Model capabilities**: Should we cache `/v1/models` response with capabilities (`thinking`, `function_calling`, `context_window`)?

---

## How to Proceed

1. Open `UPDATED_PLAN.md` (this file).
2. Pick the next concrete deliverable (recommended: **Phase 2a Streaming Foundation**).
3. Break it into small PRs or commits if desired.
4. Update this file after each major milestone (mark items complete, add new details).

---

## Quick Reference - Current Code Locations

- Entry point: `WarywayGabToolWindowFactory.kt`
- Main UI: `WarywayGabToolWindowPanel.kt`
- Settings: `WarywayGabSettings.kt`, `WarywayGabConfigurable.kt`
- Client: `GabClient.kt`
- Models: `GabModels.kt`
- Resources: `plugin.xml`, `WarywayGabBundle.properties`

Build/run: `./gradlew runIde` (or the run configuration in `.run/`).

---

**Next step recommendation:** Implement real SSE streaming + a minimal working agent loop with read-only tools and visible usage tracking. This will make the "agentic behind the scenes" experience real while keeping the UI clean.

Let's build the next piece.
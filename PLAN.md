# Waryway Gab AI Plugin for JetBrains - Implementation Plan
hello
**Repository:** `waryway-gab-plugin`  
**Date:** 2026-06-26  
**Status:** Planning

> **Note:** This is the *original* planning document.  
> For the current state and what to build next, see **[UPDATED_PLAN.md](./UPDATED_PLAN.md)**.

---

## Executive Summary

The goal is to build a "Waryway Gab" plugin for JetBrains IDEs (primarily GoLand) that provides a right-side tool window for interacting with Gab AI. The plugin will allow:

- Selecting models and "thinking levels"
- Selecting and filling out "skills"
- Streaming conversations with the AI
- Providing rich workspace context
- Making (and reviewing) code changes in the project
- Drag-and-drop support

**Current repository state:** This repo contains **no plugin code**. It only holds:
- Minimal `.idea/` configuration
- `mcps/` — a collection of JSON tool schema files describing capabilities exposed by external MCP servers (especially `goland1`, which gives deep access to GoLand via an external agent).

This is a critical starting point. We are at zero.

**Important direction (2026-06-26 update):** This will be a **specific agentic plugin for JetBrains / GoLand**. The user-facing experience should feel free and lightweight (simple chat + skills + controls). Behind the scenes it will be fully agentic: the plugin will drive multi-turn tool-calling loops using Gab AI's function calling support, executing real IDE actions (read workspace context, search, edit files safely, run builds/tests, etc.). **Context and token/credit tracking must be first-class** and always visible to the user.

The plugin should be **helpful and coaching** when the user is not yet set up (especially for authentication). If the user opens the tool window without a valid Gab AI API key, it should guide them with direct links and step-by-step directions instead of showing an empty or confusing interface.

---

## 1. User's Stated Requirements (Challenged)

User provided 12 points. Each is challenged below with questions, gaps, and implications.

1. **"This is a plugin for jetbrains. Specifically goland, but jetbrains."**
   - **Challenge:** Does "plugin" mean a first-class IntelliJ Platform plugin (Kotlin + `plugin.xml` + Tool Window) that runs *inside* the IDE process?
   - Or does it mean an external agent/TUI/client that controls GoLand using the existing `goland1` MCP server?
   - These are **radically different architectures**. One uses `com.intellij.openapi.*` APIs directly. The other talks over MCP.
   - **Impact:** Affects language, build system, UI tech, auth, deployment, permissions, and streaming entirely.

2. **"This is a waryway plugin for gab ai"**
   - **Challenge:** What is the exact product name, branding, and icon requirements?
   - Is this internal-only or intended for wider distribution?
   - Any existing Waryway design system, color palette, or component library to follow?

3. **"Identify and use the best way to interact with gab ai. It is likely through the cli or an api, but figure it out."**
   - **Confirmed (from https://gab.ai/docs):**
     - **Public Gab.ai service**: Yes. This plugin will use the relatively public gab.ai (API at `https://gab.ai/v1`).
     - **Integration surface**: REST HTTP. Primary interface is **OpenAI-compatible** (`/v1/chat/completions`). There is also explicit support for **agentic use** via `/v1/responses` ("Responses API compatibility for Codex-style agent clients and function-call loops"). Anthropic-compatible at `/v1/messages`.
     - **Streaming**: Yes, full **SSE (server-sent events)** when `stream: true`. Supports incremental `delta.content` **and** `delta.tool_calls`. Strongly recommended (especially for reasoning models) to avoid timeouts.
     - **Authentication**: `Authorization: Bearer YOUR_API_KEY`. 
  - User creates the key manually in the Gab AI web dashboard (**Settings > API Settings**).
  - Requires **Gab AI Plus** subscription.
  - The plugin does **not** perform any login flow — the user pastes their key into plugin settings.
  - **Coaching UX requirement**: The plugin should actively guide unauthenticated users with in-tool-window instructions, direct links, and step-by-step directions rather than showing a blank or broken interface.
     - **Tool / function calling**: **Full first-class support**. Pass `tools` (OpenAI shape: array of `{type: "function", function: {name, description, parameters: JSON Schema}}`). `tool_choice` supported. Models return `tool_calls` on assistant messages (or via Responses events). Multi-turn loops: append assistant + `role: "tool"` results and continue. Works across all providers (provider-agnostic translation). Streamed tool call deltas supported. Perfect for agentic behavior.
     - **Models & Thinking level**:
       - `GET /v1/models` (filterable by type) returns current list **with context windows, credit costs, and capabilities**.
       - Capabilities include booleans such as `thinking`, `function_calling`, `streaming`, `web_search`, `image_input`, etc.
       - Dedicated "Thinking" / reasoning models exist (e.g. Qwen 3.5 397B Thinking variants, DeepSeek, etc.).
       - No standard top-level `reasoning_effort` parameter is documented in core requests. "Level of thinking" will be implemented via:
         - Model selection (prefer models with `thinking: true`)
         - Possibly model-specific parameters or system instructions
         - Future: expose any additional params discovered on `/v1/models` response.
       - Arya is the built-in intelligent router (included free for Plus in-app; API usage costs credits).
     - **Context windows**: Dynamic — must fetch from `/v1/models` at runtime and display per selected model.
     - **Pricing**: **Credit-based** (not pure token $ pricing). Every response includes:
       ```json
       "usage": {
         "prompt_tokens": ..., "completion_tokens": ..., "total_tokens": ...,
         "credits_used": 2
       }
       ```
       `GET /v1/credits` for balance (monthly allotment + purchased). `GET /v1/usage` for history. Minimum 1 credit per request even for Arya in API. **Not charged for undelivered responses** if client disconnects early.
     - **Rate limits & errors**:
       - 10,000 requests/day for Plus subscribers.
       - Rate limit headers on every response: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
       - Error format: `{ "error": { "message": "...", "type": "...", "code": "..." } }`.
       - Relevant codes: `credits_exhausted` (402), `rate_limit_exceeded` (429), `plus_required`, `invalid_api_key`, `unsupported_tool_calling`, etc.
   - **Implication for this plugin**: Use an OpenAI-compatible client (or direct Ktor for full Responses API control). **The plugin itself will implement the agent loop**. This enables the desired "free-feeling UI but agentic behind the scenes". **Token + credit tracking is mandatory and should be prominent in the UI.**

4. **"The plugin sits on the right side of the jetbrains ide. It allows editing and generation of code."**
   - **Challenge:** Confirm "right side" means a `ToolWindow` docked to the right.
   - Editing vs generation: Does it directly modify files, or propose patches/diffs that the user must accept?
   - How does it handle concurrent edits, VCS state, or read-only files?

5. **"The plugin interfaces with AI, and is able to provide context of the workspace."**
   - **Challenge:** Context scope is undefined and dangerous to get wrong (token cost vs quality).
   - **Requirements for this agentic plugin**:
     - **Always track and display** current conversation token count (prompt + completion), estimated context usage vs model limit, and credits spent this turn / session.
     - Provide rich but controlled workspace context to enable strong agentic performance without user having to paste everything.
   - What context is sent automatically?
     - Open editors + selected text (high priority)
     - Current caret file + nearby symbols/functions (via GoLand PSI where possible)
     - Recent problems / build errors
     - User-selected files or symbols (explicit @-mention or drag-drop)
   - Optional deeper context (with clear cost indication): directory tree summaries, key files, git diff.
   - Smart management: token estimation before send, truncation strategies, user-visible "Context budget" meter. Prefer high-signal context.
   - How is context refreshed? On send + manual "Refresh context" + per-attachment controls.
   - User must always be able to see and remove attached context items.

6. **"The plugin keeps a list of skills which can be selected with a dropdown / filter and have a form fill for details."**
   - **Challenge:** Skills system is completely undefined. (Now informed by agentic + tool calling.)
   - **Recommended design for agentic plugin**:
     - Skills = **named bundles of tools + instructions + form schema**.
     - When user selects a skill and fills the form, the plugin:
       - Constructs `tools: [...]` array from the skill's tool definitions (parameterized by form values)
       - Injects skill-specific system prompt / user message
       - Starts (or continues) an agentic loop
     - Example skills: "Implement feature from spec", "Write tests for current file", "Refactor selection", "Explain codebase area", "Fix build errors".
     - Source: Start with local JSON/YAML (project + user dir) + a small set of built-in skills. Later allow user creation / import.
     - Dynamic forms: text, textarea, boolean, select, file-path picker (relative to project), multi-select.
     - Filtering: search + tags/categories.
   - Because Gab supports real `tools`, skills should produce executable tool definitions that the plugin can fulfill using GoLand APIs (not just prompt text).

7. **"The model can be selected from a dropdown."**
   - **From docs**: Dynamic via `GET /v1/models`.
   - Must fetch live (caches ok with refresh button).
   - Display useful info per model: name, provider, context window size, supports function_calling / thinking / vision, estimated credit cost.
   - Remember last-used model (per project preferred, global fallback).
   - Filter UI by capabilities (e.g. "Only show models with tool support", "Thinking models").

8. **"The level of thinking can be selected as well."**
   - **From docs:** Models expose a `thinking` capability. There are dedicated thinking/reasoning models.
   - **Implementation approach**:
     - Provide a "Thinking" toggle or dropdown (None / Standard / Deep / Model default) that:
       - Filters or prefers models with `thinking: true`
       - May append stronger reasoning instructions to system prompt
       - Selects known thinking model variants when possible
     - No universal `reasoning_effort` param is documented; pass any model-specific fields discovered via `/v1/models`.
     - Always surface when a "thinking" model is active (they can take >1 minute — rely on streaming).
   - Apply at session start or per-message (user choice). Default to user's previous selection.

9. **"Stop is an option if it is running."**
   - **Challenge:** Cancellation semantics.
   - Abort the current streaming request?
   - Also stop any in-flight code edits or tool use?
   - Visual state (generating vs idle)?
   - Keyboard shortcut for stop?

10. **"The active session is streamed."**
    - **Confirmed**: SSE via `stream: true` on chat completions (and Responses API).
    - Supports both content deltas **and** partial `tool_calls` (critical for agentic UX — show "AI is deciding to use skill X..." live).
    - Plugin must:
      - Render incremental markdown + syntax-highlighted code blocks as they arrive.
      - Handle partial tool calls gracefully (don't surface raw until complete or user-configurable).
      - Show separate "thinking" or internal traces if the model emits them (some reasoning models do).
      - Support clean cancellation (abort the HTTP/SSE connection).
    - Set generous client read timeouts (120–210s recommended in docs for reasoning models).
    - Reconnect/resume: not natively supported by the API for a single call — the plugin should allow user to continue the conversation manually after interruption (history is preserved).

11. **"Code in the project can be changed."**
    - **Challenge:** Safety and UX for modifications.
    - Preferred mechanisms inside a real plugin:
      - `Document` / `PsiFile` manipulation via IntelliJ APIs
      - Write via `FileDocumentManager`
      - Or generate unified diffs / use `apply_patch` style if bridging via MCP
    - Should changes be:
      - Applied immediately (with undo)?
      - Shown as a diff in a preview editor first?
      - Written to new files only in some cases?
    - How are multi-file changes handled (transactions, atomicity)?
    - Conflict detection with unsaved changes or external edits?
    - Should the plugin offer "Apply", "Apply All", "Reject", "Review in editor"?

12. **"Drag and drop into the interface can be changed."**
    - **Challenge:** What can be dragged?
    - Files from Project view?
    - Text selections from editor?
    - Images / screenshots?
    - Symbols or PSI elements?
    - What does dropping do? Add to context? Start a skill? Trigger generation?
    - Cross-platform drag-and-drop behavior in Swing/Compose.

---

## 2. Major Unstated / Missing Concerns

The original 12 points are missing critical areas:

- **Authentication & Configuration**
  - Where do users enter Gab AI credentials / endpoint?
  - Global vs per-project settings?
  - Secret storage (IntelliJ PasswordSafe recommended)?
  - Proxy / corporate network settings?
  - **Active coaching for unauthenticated state**: When no valid key is configured, the tool window should coach the user with clear steps, a direct link to https://gab.ai, and instructions on how to create a Plus account + generate an API key (Settings > API Settings). Do not just show an empty chat or cryptic error. Provide inline "Paste key here" or quick-open-settings affordance. Show different states (no key, invalid key, valid but low credits, etc.).

- **Chat / Session Management**
  - Multiple conversations?
  - History persistence (project-level or global)?
  - Export / share transcript?
  - Forking conversations?
  - Token usage display?

- **Safety & Permissions**
  - Does the AI get unrestricted write access?
  - Confirmation dialogs for destructive actions (delete, large refactors)?
  - Scope restrictions (only within certain directories)?
  - Audit log of AI-made changes?

- **Context Engineering & Cost Control**
  - Automatic context summarization?
  - File exclusion patterns (node_modules, .git, build dirs)?
  - @-mentions or explicit file references in chat?

- **Skills Authoring Experience**
  - How does a Waryway team member add a new skill?
  - Validation of skill parameter schemas?
  - Testing skills without running full generation?

- **UI/UX Completeness**
  - Loading states, error banners, retry
  - Markdown rendering + copy code blocks
  - Syntax highlighting for proposed code
  - "Insert at cursor", "Replace selection", "Create new file" actions on code blocks
  - Accessibility (keyboard navigation)

- **Build, Packaging, Distribution**
  - Gradle + IntelliJ Platform Gradle Plugin (standard)
  - Minimum supported versions (GoLand 2024.3+, 2025.x?)
  - Publishing to JetBrains Marketplace or private distribution?
  - Signing?

- **Testing Strategy**
  - Unit tests for services
  - UI tests (difficult)
  - Integration with real IDE (lightweight test IDE or full fixture)
  - MCP tool usage testing (if that path is chosen)

- **Observability**
  - Logging of requests/responses (with redaction)
  - Error reporting
  - Optional telemetry (opt-in)

- **Dependencies on MCP vs Native**
  - Native plugin uses IntelliJ Platform APIs directly for all IDE interactions.
  - The `mcps/goland1/*.json` files are **reference only** (inspiration for what powerful tools an agent can have in GoLand).
  - No runtime dependency on the MCP servers for the plugin itself.

---

## 3. Architecture Decision: The Most Important Fork

Two viable paths exist. We must pick one (or a deliberate hybrid).

### Path A: Traditional JetBrains Plugin (Recommended for "sits on the right side")

- Language: Kotlin
- Build: Gradle + `org.jetbrains.intellij.platform` plugin
- UI: Tool Window (`ToolWindowFactory`) on the right, using Swing or JetBrains Compose (experimental)
- AI client: HTTP client (OkHttp / Ktor) talking directly to Gab AI (or CLI subprocess if chosen)
- Context: Use `Project`, `Editor`, `PsiManager`, `FileIndex`, `DaemonCodeAnalyzer` etc. directly
- Edits: Use `WriteCommandAction`, `Document`, `PsiDocumentManager`
- Streaming: SSE or WebSocket client inside the plugin
- Packaging: Produces a `.zip` plugin or published to Marketplace

**Pros:** Matches user's mental model of a "plugin on the right". Full IDE integration. Best UX.
**Cons:** More boilerplate. Harder to test. Must handle IDE lifecycle.

### Path B: External Client + MCP (Leverages existing `mcps/` artifacts)

- The "UI" lives outside (or is a thin window), talks to Gab AI, and uses `goland1` MCP (and possibly others) to inspect/edit the project.
- The existing JSON schemas become the contract.
- Example: Extend something like the existing Grok CLI/TUI or build a new Electron/desktop app or even a webview that speaks MCP.

**Pros:** Can reuse some MCP tooling. Potentially faster iteration outside IDE constraints.
**Cons:** Does **not** feel like "a plugin sitting on the right side of the IDE". Requires users to run extra processes. Drag-drop and deep editor integration harder. Streaming UX bridging is complex.

**Recommendation for this project:** **Path A (real in-process JetBrains plugin)**.

Rationale:
- User explicitly wants the UI to "sit on the right side of the jetbrains ide".
- Full native access to GoLand/IntelliJ Platform APIs is ideal for a powerful **agentic** experience (direct PSI, safe document writes with undo, run configs, inspections, project model, etc.).
- Gab AI's OpenAI + Responses + tool calling APIs are trivial to call from Kotlin inside the plugin.
- The existing `mcps/goland1` JSONs become **excellent reference material** for the set of tools the plugin should expose to the model (read_file, search, apply_patch, build, get_problems, etc.). We will implement equivalent native actions.

**Agentic core requirement**:
- The plugin will maintain a full message history.
- When the model returns `tool_calls` (or Responses function_call items), the plugin executes the corresponding GoLand action(s), appends `role: "tool"` results, and loops until `finish_reason != "tool_calls"`.
- User sees a clean streaming chat; the agentic loop happens transparently behind the scenes.
- All tool executions and context injections must contribute to accurate token/credit accounting shown to the user.

**Token & context tracking (mandatory)**:
- On every API response, capture and accumulate `usage.prompt_tokens`, `completion_tokens`, `total_tokens`, `credits_used`.
- Before each send, estimate current history + injected context size against the selected model's context window.
- Display live: Session tokens, last-turn cost, cumulative credits, context usage %.
- Offer controls to prune history or remove context attachments.

---

## 4. Token, Credit & Agentic Tracking Requirements (Core to This Plugin)

Because the plugin must feel **free to use** while being **agentic** under the hood:

- **Mandatory UI elements**:
  - Live token counter: current prompt + completion for the turn + running session total.
  - Credits used this turn / session total. Show balance when possible (`/v1/credits`).
  - Context usage: "12,450 / 128,000 tokens (9.7%)" for the selected model.
  - Per-message breakdown in history (hover or expand).
  - Warning banners before sending when approaching limits or high credit cost.

- **Agent loop visibility** (without overwhelming the user):
  - During streaming, show status like "Thinking...", "Using tool: read_file src/main.go", "Applying edit...".
  - Optional "Show agent trace" toggle that reveals the full message history with tool calls/results.
  - User can always interrupt (Stop).

- **Context management**:
  - Explicit attachments (files, selections, symbols) are listed and removable.
  - Automatic context is summarized or truncated intelligently.
  - Before every request, the plugin estimates tokens for the payload (history + tools + injected context) and may offer to prune or summarize.

- **Implementation notes**:
  - Use the `usage` object returned on every response (even streamed final chunk).
  - Maintain accurate client-side token accounting (use approximate tokenizer or request count_tokens where available; Anthropic compat has `/messages/count_tokens`).
  - Record history of turns with their exact usage for auditing.

## 5. Gab AI Integration Research Tasks (Must Do Early)

1. Confirm access: A user needs a **Gab AI Plus** subscription ($20/mo or $150/yr). They create the API key themselves at https://gab.ai → Settings > API Settings (the plugin will never do this for them). The plugin only receives and stores the key the user supplies.
2. Prototype the client (Kotlin inside the future plugin or a small standalone test):
   - Auth with Bearer key against https://gab.ai/v1
   - Call `GET /v1/models` and inspect context windows + capabilities (especially `thinking` and `function_calling`).
   - Send a simple chat completion with `stream: true`.
   - Test a full tool-calling loop (send tools, receive tool_calls, send results, continue) using both `/chat/completions` and `/responses`.
   - Exercise `/v1/credits` and inspect `usage.credits_used` + rate limit headers on responses.
3. Decide on client library: OpenAI-compatible Kotlin SDK (if sufficient) vs custom Ktor client (for full Responses API + SSE control).
4. Document integration details, error handling patterns, and token/credit extraction in the codebase.
5. Verify long-running reasoning model behavior with streaming + generous timeouts.

---

## 6. Skills System Design (High Priority)

Proposed initial model:

- Skill definition (JSON or Kotlin data class + serialization):
  ```json
  {
    "id": "implement-feature",
    "name": "Implement Feature",
    "description": "Implement a feature from a short spec",
    "category": "code",
    "parameters": [
      { "name": "featureName", "type": "string", "required": true, "label": "Feature Name" },
      { "name": "description", "type": "text", "required": true },
      { "name": "targetPackage", "type": "string", "required": false },
      { "name": "includeTests", "type": "boolean", "required": false, "default": true }
    ],
    "systemPromptTemplate": "...",
    "userPromptTemplate": "..."
  }
  ```
- Storage: `~/.waryway-gab/skills/` (user) + project-level `.waryway/skills/` (team)
- UI: Combo box / searchable list + dynamic form generated from parameters.
- Execution: Skill + form values → prompt construction → send to Gab AI (with workspace context).

Later: remote skill catalog, versioning, skill marketplace.

---

## 7. High-Level Phased Plan

### Phase 0: Setup & Decisions (1-2 days)

- [ ] Confirm Path A (native plugin) and Gab AI Plus key availability.
- [ ] Gab AI integration spike:
  - Live `/v1/models`, streaming chat, full tool calling loop, credits + usage extraction.
  - Prototype token estimation + display.
- [ ] Decide minimum IDE version (GoLand primary).
- [ ] Create Gradle + IntelliJ Platform plugin skeleton.
- [ ] Basic structure + dependencies (HTTP client with SSE support, JSON, markdown rendering, IDE write APIs).
- [ ] Design the core data model for: Conversation (with usage tracking), ContextAttachment, Skill, AgentLoop state.

### Phase 1: Basic Tool Window + Settings + Onboarding Coaching (3-5 days)

- [ ] Right-side Tool Window with chat area + controls bar (model, thinking, skills dropdown, stop).
- [ ] **Unauthenticated coaching / onboarding state**:
  - When no API key is configured (or key is invalid), replace the chat UI with a clear coaching panel.
  - Include:
    - Friendly message: "Welcome to Waryway Gab for GoLand. To get started you need a Gab AI API key."
    - Direct clickable link to https://gab.ai (use `BrowserUtil.browse`)
    - Step-by-step directions (exact):
      1. Go to https://gab.ai and create/log in to an account
      2. Upgrade to a **Plus** plan (required for API access)
      3. Click **Settings** (top right) → **API Settings**
      4. Click to generate a new API key (the full key is shown only once — copy it)
      5. Paste the key in the field below or open the plugin Settings
    - Prominent "Paste your API key" input field directly in the panel.
    - "Open Plugin Settings" button.
    - "Test & Continue" action that validates the key immediately.
  - After key is entered, automatically test it and transition to normal chat view on success.
- [ ] Basic chat UI with streaming text (shown only when authenticated).
- [ ] Prominent token/credit/context usage display (even if approximate initially).
- [ ] Settings dialog / panel:
  - API key input (stored securely with PasswordSafe, masked)
  - "Test connection" button (calls /v1/models or /v1/credits)
  - Helpful text + hyperlink: "Get your key at gab.ai → Settings > API Settings (requires Plus subscription)"
  - Default model + thinking preference
- [ ] Dynamic model dropdown (fetched from /v1/models, showing key capabilities + context size).
- [ ] Stop / cancel for the current request.
- [ ] Persist recent conversations (at least in-memory for now, later project-level).
- [ ] Handle error states gracefully (invalid key → show coaching banner with link back to settings).

### Phase 2: Streaming + Core Agentic Chat Loop (4-6 days)

- [ ] Full streaming client (content + tool_calls deltas).
- [ ] Agent loop implementation: when finish_reason == "tool_calls", execute tools using IDE services, append results, continue the conversation.
- [ ] Basic built-in tools: read file, list dir (summary), search text, get open files/selection.
- [ ] Incremental markdown + code highlighting.
- [ ] Live update of token/credit counters from usage objects.
- [ ] Error handling, rate limit / credit messages, retry.

### Phase 3: Workspace Context (4-7 days)

- [ ] Context gathering service
  - Current file + selection
  - Open files
  - Optional: project structure summary, recent git changes, compiler errors
- [ ] Context inclusion UI (checkboxes, chips, "Attach file", "@file" syntax)
- [ ] Smart truncation / prioritization to stay under token budgets
- [ ] Exclusions (gitignore aware + user config)

### Phase 4: Skills System (5-8 days)

- [ ] Skill definition format + loader
- [ ] Skills dropdown + filter/search
- [ ] Dynamic form renderer based on parameter schema
- [ ] Skill execution flow (injects into prompt)
- [ ] Default set of useful skills (explain code, generate test, implement from comment, refactor, etc.)
- [ ] User-defined skills (save/load)

### Phase 5: Code Modification & Agentic Edits (5-8 days)

- [ ] Safe edit tools that the agent can call: replace_text, apply_patch (unified diff), create file, etc. using proper WriteCommandAction + undo support.
- [ ] Modification UX: Prefer showing a clear summary + "Apply" / "Preview diff" / "Reject" for larger changes. Allow auto-apply for small trusted ones (configurable).
- [ ] Code block affordances in chat + tool results.
- [ ] Multi-file edits + post-edit build/problems check (optional).
- [ ] Drag & drop: files/selections → context attachments (or trigger skill).
- [ ] All edits performed by agent tools are recorded with token/credit impact.

### Phase 6: Polish, Safety, Persistence (4-7 days)

- [ ] Chat history persistence (per project or global)
- [ ] Conversation list / switching
- [ ] Stop during tool use / long generations
- [ ] Confirmation for large or risky edits
- [ ] Better error surfaces and recovery
- [ ] Keyboard shortcuts
- [ ] Theming consistency with IDE

### Phase 7: Packaging, Docs, Testing (ongoing)

- [ ] Build and run in real GoLand (sandboxed)
- [ ] Basic automated tests (client layer, skill loader, context builder)
- [ ] User documentation (README + in-plugin help)
- [ ] Plugin metadata, icon, description
- [ ] Release process (local install, then distribution)

---

## 8. Open Questions / Decisions Required (Answer These)

Answer these explicitly before or during Phase 0. (Many now informed by docs.)

1. Architecture confirmation: Real in-IDE Kotlin plugin (Path A) — **strongly recommended and aligned with "sits on the right" + agentic GoLand focus**.
2. Gab AI details — largely answered from docs (see section 3). Confirm:
   - Do we get a Plus account / API key for development?
   - Any Waryway-specific wrapper or direct use?
3. Skills source and ownership (start with local files + built-ins; user-editable later).
4. Code change policy: auto-apply (with undo + visible diff) vs always preview? Recommend configurable with safe default (preview for destructive, auto for small).
5. Workspace context defaults and pruning strategy.
6. Minimum IDE version (target recent GoLand + general JetBrains support where easy).
7. VCS / Local History integration for AI edits.
8. Role of the existing `mcps/` folder: reference/docs only (not runtime dependency for the native plugin).
9. **Token/credit UI requirements** (confirmed mandatory): live session totals, per-turn breakdown, context % meter, warnings before large sends. Any spend limits or approvals?
10. (new) Should the plugin support entering the key + also support environment variable fallback (e.g. `GAB_API_KEY`)? (Common in such plugins)
10. Branding: plugin id (`com.waryway.gabplugin` or similar), display name ("Waryway Gab" or "Gab Agent"), icon.
11. Should the plugin expose its own tools to Gab via the standard `tools` mechanism only, or also support Responses API items for richer agent clients? (Start with chat + tools, add Responses later.)

---

## 9. Risks and Mitigations

| Risk | Likelihood | Mitigation |
|------|------------|----------|
| Gab AI integration surface is undocumented or unstable | High | Early spike + abstraction layer |
| Token context explosion | High | Aggressive context selection + summarization + user controls |
| Users lose trust after bad auto-edits | Medium | Default to preview/confirm for edits; strong undo |
| Drag & drop is flaky across platforms | Medium | Implement file + text first; images later |
| Testing a real plugin is hard | Medium | Isolate business logic; use IntelliJ test fixtures |
| Skills become unmaintainable | Low | Start simple, version the schema |

---

## 10. Non-Goals (For Now)

- Full multi-agent orchestration
- Vision / image upload (unless Gab AI supports it strongly)
- Built-in fine-tuning or prompt optimization UI
- Marketplace publication in first release
- Supporting every JetBrains IDE on day one

---

## 11. Success Criteria

- User can open the Waryway Gab tool window on the right in GoLand.
- If no API key is configured, the tool window **coaches** the user with clear steps, a working link to https://gab.ai, and easy way to paste the key (or jump to settings).
- Select model + thinking mode.
- Browse and fill a skill form; the skill results in real tool calls executed against the workspace.
- Have a streamed conversation that transparently performs agentic actions (reading files, searching, editing) while the user sees clean output.
- **Token, credit, and context tracking is always visible and accurate** (per-turn + session).
- AI-proposed (or tool-executed) code changes actually modify the project safely (with undo).
- Drag and drop works for context.
- Stop aborts the current generation and any pending tool executions cleanly.
- Rate limit / credit exhaustion errors are handled gracefully with clear messages.
- All of the above works without crashing the IDE and respects GoLand's write/command model.

---

## Appendix: Current Repo Assets

The `mcps/goland1/tools/` directory contains ~40 JSON schemas. These describe a very powerful external control surface (read file, search, replace, patch, build, run configs, PSI info, refactor rename, etc.).

These are **not** required for the native plugin at runtime.

**Current role**: High-quality reference for the exact IDE capabilities a strong agent should have access to (read, search, edit, build, PSI, refactor, run configs, etc.). Use them to design the plugin's internal tool implementations.

Decision: Keep `mcps/goland1` for reference. Implement using native `com.intellij.*` APIs.

---

**Next step:** Resolve the architecture decision and Gab AI integration details, then begin Phase 0 scaffolding.

# Plan — Improve Grok Build interaction (near-flawless)

**Project slug:** improve-grok-build-interaction  
**Directive:** `.grok/org/improve-grok-build-interaction/directive.md`  
**Status:** done  
**Director round:** 1 (verify)

## Executive summary

Make the **Grok Build local/session path** in the Waryway Gab JetBrains plugin feel near-flawless end-to-end: auth/session (`grok login` / `~/.grok/auth.json`), OpenAI-compatible chat streaming via cli-chat-proxy **and** Local LLM chat, Local LLM `/api/agent` (plan+tools, dry-run vs APPLY), workbench/send UX, actionable errors, Stop/cancel, and focused regressions. Prior projects (`grok-support-agent-stability`, `grok-build-stream-fix`) already landed core stream merge, UI coalescing, and cloud AgentSession stability — this plan **verifies remaining gaps** and polishes recovery/UX rather than redoing those cores. Five concert-friendly sections: auth/session, chat stream residual, agent API, UX/recovery copy, then regression + ship (0.1.29 → **0.1.30**).

## Discovery notes (Director)

### Surface map (current tree, plugin **0.1.30** shipped)

| Area | Key types / paths | Current state |
|------|-------------------|---------------|
| Provider | `ModelProvider.GROK_BUILD` → `https://cli-chat-proxy.grok.com/v1`; `LOCAL_LLM` → configurable `…/v1` | First-class enum; separate from `GROK` (api.x.ai) |
| Auth | `client/GrokBuildAuth.kt`, `GrokBuildAuthRecovery.kt`, `settings/WarywayGabSettings` | Live session source of truth; recovery coaching; refresh without restart |
| Settings UI | `WarywayGabConfigurable` group “Grok Build” | Live summary + **Refresh session** |
| Chat client | `GabClient` + `GabSseAccumulator` + `StreamContentMerger` | GROK_BUILD headers; SSE abort on Stop |
| Stream UI | `StreamUiCoalescer`, `ChatMessageListPanel` | ~40ms coalesce; soft caps |
| Server agent | `AgentClient` + `LocalLlmAgentSession` + `AgentRunOps` | Terminal format helpers; cancel/timeout clear active run |
| Workbench | `LocalLlmWorkbenchPanel`, `LocalLlmSendUx` | Agent mode / Apply path badges |
| Tool window | `WarywayGabToolWindowPanel` | GROK_BUILD session chip + failure UX; Local path badges; Stop cancel |
| Failure UX | `GrokBuildSendUx`, `GrokBuildChatFailureUx` | Auth/network recovery copy single-sourced via `GrokBuildAuthRecovery` |

### Prior work — do not re-implement blindly

| Project | Landed | Residual risk for this goal |
|---------|--------|----------------------------|
| `grok-build-stream-fix` (done WOs, 0.1.27) | `StreamContentMerger`, `StreamUiCoalescer`, package | Guarded by regression tests in this project |
| `grok-support-agent-stability` (done) | Cloud Grok path + `AgentSession` exit taxonomy + SSE tool fidelity | Out of scope; not reworked |

### Remaining gaps (closed in sections 01–05)

1. **Auth recovery** — closed: pure `GrokBuildAuthRecovery`, refresh API, 401/403 mapping, settings refresh.
2. **Chat path error asymmetry** — closed: `GrokBuildSendUx` / `GrokBuildChatFailureUx` on send catch paths.
3. **Agent API terminal coverage** — closed: pure finish/status helpers + 35 `LocalLlmAgentSessionTest` cases.
4. **UX / workbench** — closed: session chip for GROK_BUILD; Local path labels retained.
5. **Ship / docs** — closed: `pluginVersion=0.1.30`, CHANGELOG, README, HOW_TO_LOAD stamps.

## Major phases

| Phase | Section ID | Title | Owner | Status | Depends on |
|-------|------------|-------|-------|--------|------------|
| 1 | section-01-auth-session | Grok Build auth & session reliability | Manager | done | — |
| 1 | section-02-chat-stream | Chat streaming residual polish (proxy + shared SSE) | Manager | done | — |
| 1 | section-03-agent-api | Local LLM `/api/agent` terminal fidelity | Manager | done | — |
| 1 | section-04-ux-recovery | Workbench / send UX & recovery copy | Manager | done | — |
| 2 | section-05-regression-ship | Cross-cutting tests + version/docs ship | Manager | done | soft: 01–04 |

## Section roster

### section-01-auth-session — Grok Build auth & session reliability

- **Path:** `.grok/org/improve-grok-build-interaction/sections/section-01-auth-session.md`
- **Goal:** Session discovery, expiry, override, and reconnect coaching work without IDE restart; missing/stale session is self-explanatory.
- **Acceptance:** Usable-session checks correct; reconnect/refresh without restart; clear coaching for missing/expired; no blind reimplementation of parser if already sufficient.
- **Manager playbook:** `general`
- **Status:** done

### section-02-chat-stream — Chat streaming residual polish

- **Path:** `.grok/org/improve-grok-build-interaction/sections/section-02-chat-stream.md`
- **Goal:** GROK_BUILD + Local chat completions stream correct once (no thrash/RAM spike); failures surface recoverable reasons; preserve StreamContentMerger/Coalescer contracts.
- **Acceptance:** No regression of snapshot merge; GROK_BUILD chat errors actionable; Stop still aborts SSE; focused tests for any new pure helpers.
- **Manager playbook:** `general`
- **Status:** done

### section-03-agent-api — Local LLM agent path fidelity

- **Path:** `.grok/org/improve-grok-build-interaction/sections/section-03-agent-api.md`
- **Goal:** `LocalLlmAgentSession` + `AgentClient` always end with clear terminal content/status; cancel works; dry-run vs APPLY reflected in status/final text.
- **Acceptance:** No silent mid-run death; cancel/timeout/failed paths unit-tested where pure; Stop posts cancel.
- **Manager playbook:** `general`
- **Status:** done

### section-04-ux-recovery — Workbench / send UX & recovery copy

- **Path:** `.grok/org/improve-grok-build-interaction/sections/section-04-ux-recovery.md`
- **Goal:** Chat vs Agent · dry-run/APPLY obvious; Grok Build and Local errors tell operators what to do next; session/status visible in the main path.
- **Acceptance:** Path labels/tooltips consistent; recovery strings for login / base URL / offline proxy; pure UX helpers + tests preferred.
- **Manager playbook:** `general`
- **Status:** done

### section-05-regression-ship — Tests + package readiness

- **Path:** `.grok/org/improve-grok-build-interaction/sections/section-05-regression-ship.md`
- **Goal:** Close test gaps for new behavior; green scoped suite; version bump + CHANGELOG/HOW_TO notes if code shipped.
- **Acceptance:** New/changed pure logic covered; `./gradlew test` (or documented filter) green; version/docs updated when sources change.
- **Manager playbook:** `general`
- **Status:** done

## Risks & assumptions

- **Assumption:** “Grok Build interaction” includes both `ModelProvider.GROK_BUILD` (cli-chat-proxy session) and the Local LLM workbench/`/api/agent` path that operators use alongside it — not cloud `GROK` (api.x.ai) rework. **Held.**
- **Risk:** Parallel sections may touch `WarywayGabToolWindowPanel.kt` — mitigated; pure helpers preferred; compile + 292 tests green.
- **Risk:** Cannot fully E2E live `cli-chat-proxy` or LocalLLM server in CI — pure unit/contract tests used; residual e2e/manual smoke remains operator-side.
- **Assumption:** StreamContentMerger / StreamUiCoalescer need **regression guard**, not redesign. **Held.**
- **Constraint:** No unrelated refactors; single-repo; max 3 Manager iterations per section before escalate. **Held** (all sections 1 iteration).

## Director verification log

| Round | Sections done | Blockers | Notes |
|-------|---------------|----------|-------|
| 0 | 0 / 5 | — | Plan drafted; discovery complete; managers-queue written |
| 1 | 5 / 5 | — | **MODE=verify PASS.** All section tickets `done`; managers-queue all `done`. Directive success criteria met by artifacts + verifier reports. Full suite **292/292**; `pluginVersion=0.1.30`. Residual: no live E2E against cli-chat-proxy (by design; unit coverage only). |

### Verify checklist (Director round 1)

| Directive success criterion | Result | Evidence |
|-----------------------------|--------|----------|
| Grok Build connect/auth reliable & self-explanatory when missing/stale | **met** | `GrokBuildAuthRecovery` + `refreshGrokBuildSession` + settings Refresh + tool-window coaching; tests 12+10 |
| Chat streaming correct once (no runaway / memory spike) | **met** | Merger/coalescer/SSE tests green; no redesign; CHANGELOG guard note |
| Agent `/api/agent` clear terminal; Stop/cancel works | **met** | `formatFinalContent` / `formatTerminalStatus` / `cancelActiveRun` / timeout; 35 session tests |
| Dry-run vs APPLY and chat vs agent paths obvious | **met** | `LocalLlmSendUx.sendPathLabel` + workbench + send badge wire |
| Errors guide recovery (login, base URL, offline proxy) | **met** | `GrokBuildSendUx` / `GrokBuildChatFailureUx` / `LocalLlmSendUx` on send catch paths |
| Focused regression tests; in-scope green | **met** | Verifier full suite 292 passed (`verifier-report-section-05.md`) |
| No unrelated refactors; single-repo | **met** | Scoped helpers + tests + ship docs only |
| Version bump + CHANGELOG if code shipped | **met** | `pluginVersion=0.1.30`; CHANGELOG `## [0.1.30]`; README / HOW_TO_LOAD stamps |

### Section Director sign-off (artifacts)

| Section | Status | Key artifacts |
|---------|--------|---------------|
| 01 auth-session | done | `GrokBuildAuthRecovery.kt`, recovery tests, refresh settings API |
| 02 chat-stream | done | Stream contracts preserved; chat failure UX path |
| 03 agent-api | done | `AgentRunOps`, terminal pure helpers, 35 session tests |
| 04 ux-recovery | done | `GrokBuildSendUx`, `GrokBuildChatFailureUx`, workbench/path labels |
| 05 regression-ship | done | +3 `requestHeaders` tests; 0.1.30 ship docs |

**Director sign-off:** done  
**Director signal:** `DIRECTOR_DONE` (2026-07-17)

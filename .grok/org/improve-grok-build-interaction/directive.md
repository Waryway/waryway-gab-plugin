# Directive — Improve Grok Build interaction (near-flawless)

**Project slug:** improve-grok-build-interaction  
**Status:** done  
**Created:** 2026-07-17  
**CEO round:** 1

## Human direction

Improve **Grok Build** interaction in this JetBrains plugin (Waryway Agent / waryway-gab-plugin) so it works **almost flawlessly**.

Grok Build is the local/session path (`grok login`, `~/.grok/auth.json`, cli-chat-proxy / Local LLM-style base URL, workbench, `/api/agent` and chat completions) — **not** only the cloud xAI API provider path.

The product must feel reliable end-to-end: connect/auth, model selection, chat streaming, agent mode (plan+tools), stop/cancel, UI feedback, errors, and recovery. Prior partial work landed; this directive demands **near-flawless** polish, not another partial fix.

### Prior related work (do not redo blindly — verify gaps remain)

| Project | Status | What it covered |
|---------|--------|-----------------|
| `grok-support-agent-stability` | **done** | Cloud Grok (xAI) provider, SSE/tool fidelity, `AgentSession` premature-stop, regression tests |
| `grok-build-stream-fix` | work orders marked done | Cumulative SSE snapshot merge, UI coalescing, package **0.1.27** |

### Focus areas

1. **Auth & session** — `grok login` discovery, session token override, clear coaching when missing/expired, reconnect without restart.
2. **Chat path** — OpenAI-compatible stream to Grok Build proxy: correct deltas/snapshots, no UI thrash/RAM spike, full final text once.
3. **Agent path** — `LocalLlmAgentSession` + `/api/agent`: start/poll/cancel, dry-run vs APPLY, tool progress, no silent mid-run death, clear terminal status.
4. **UX & workbench** — LocalLlm workbench badges, send path clarity, status lines, errors that tell the user what to do next.
5. **Hardening & tests** — unit tests for pure helpers; regression for stream merge, agent session edge cases, send UX; suite green for in-scope packages.
6. **Ship readiness** — version bump if code changes, CHANGELOG/HOW_TO_LOAD notes so Install-from-Disk is distinguishable.

## Success criteria (CEO defines with human)

- [x] Grok Build connect/auth path is reliable and self-explanatory when session is missing or stale
- [x] Chat streaming shows correct content once (no runaway repeat / memory spike)
- [x] Agent mode (`/api/agent`) completes or fails with a clear terminal reason; Stop/cancel works
- [x] Dry-run vs APPLY and chat vs agent send paths are obvious and correct
- [x] Error messages guide recovery (login, base URL, offline proxy)
- [x] Focused regression tests for new/changed behavior; in-scope tests green
- [x] No unrelated refactors; single-repo scope
- [x] If code shipped: version bump + short CHANGELOG note → **0.1.30**

## Constraints

- Single-repo scope: waryway-gab-plugin
- Route via project context (`README.md`, `HOW_TO_LOAD_IN_IDE.md`, existing Local LLM / Grok Build code under `chat/`, `client/`, `ui/`, `settings/`) — focused discovery, not full-repo rewrite
- Prefer pure helpers + unit tests over brittle UI integration tests
- CEO never edits source code — only tickets, JSON queues, orchestration
- Tests must pass (scoped acceptable if OOS pre-existing failures documented) before CEO marks done

## CEO verdict log

| Round | Date | Verdict | Notes |
|-------|------|---------|-------|
| 0 | 2026-07-17 | pending | Directive issued |
| 1 | 2026-07-17 | **satisfied** | Director DIRECTOR_DONE; 5/5 sections done; full suite 292 green; ship **0.1.30** |

## Director handoff summary

- Plan path: `.grok/org/improve-grok-build-interaction/plan.md` (Status=done)
- Sections complete: **5 / 5**
- Blockers: none

### What landed

| Area | Change |
|------|--------|
| Auth/session | `GrokBuildAuthRecovery`; live-prefer token; `refreshGrokBuildSession`; Configurable Refresh; tool-window coaching |
| Chat stream | Stream contracts locked; GROK_BUILD headers pure map; failure UX via `GrokBuildSendUx` / `GrokBuildChatFailureUx` |
| Agent API | Terminal format helpers; `AgentRunOps` cancel/timeout; unit-tested poll seam |
| UX | Session status chip; no bare Error messages; Local-only agent badges |
| Ship | **0.1.30** + CHANGELOG + README/HOW_TO_LOAD; full suite **292** pass |

## CEO satisfaction checklist

- [x] All sections in `plan.md` are `done`
- [x] Success criteria above are met
- [x] Project test suite green (292/292 full suite)
- [x] Human has not objected in this round

**CEO final status:** done

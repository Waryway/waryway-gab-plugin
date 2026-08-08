# Directive — Grok support & agentic loop stability

**Project slug:** grok-support-agent-stability  
**Status:** done  
**Created:** 2026-07-17  
**CEO round:** 1

## Human direction

This repo contains a JetBrains AI agentic plugin (Waryway Agent / waryway-gab-plugin). Goals:

1. **Better Grok support** — The plugin already does some Grok (xAI) work (models, provider, defaults). Improve first-class Grok experience: API/provider integration, model catalog, streaming, tool-calling/agent loop compatibility, settings/UX, and any gaps vs Gab or other providers so Grok is a reliable default path.

2. **Stop random mid-process agentic stops** — The agentic process sometimes ends in the middle of processing. It does **not** get stuck (a new message can still be sent); the agent loop should not terminate randomly before the task is complete. Investigate and fix premature session/loop exit: streaming end handling, max-steps/timeouts, error swallowing, tool-result continuation, cancel/done flags, provider-specific finish reasons, empty responses, etc.

## Success criteria (CEO defines with human)

- [x] Grok (xAI) path is clearly first-class: correct models, auth, request/response shape, and tool/agent loop behavior documented in code/tests where appropriate
- [x] Agentic multi-step runs do not stop without a clear terminal reason (user cancel, hard error with message, or model `stop`/`end_turn` with no pending tools)
- [x] Premature-stop root causes fixed with regression tests where feasible (loop control, finish reasons, empty stream, tool-call continuation)
- [x] Existing test suite green for in-scope packages; full suite 2 OOS pre-existing fails documented (`InputNormalizerTest`, `GolandMcpExecutorTest`)
- [x] No unrelated refactors; single-repo scope

## Constraints

- Single-repo scope: waryway-gab-plugin
- Route via project context (`README.md`, `PLAN.md`, existing Grok/agent session code) — focused discovery, not full-repo rewrite
- Tests must pass before CEO marks done
- CEO never edits source code — only tickets, JSON queues, orchestration

## CEO verdict log

| Round | Date | Verdict | Notes |
|-------|------|---------|-------|
| 0 | 2026-07-17 | pending | Directive issued |
| 1 | 2026-07-17 | **satisfied** | Director DIRECTOR_DONE; 4/4 sections done; scoped chat/client/model tests green |

## Director handoff summary

- Plan path: `.grok/org/grok-support-agent-stability/plan.md` (Status=done)
- Sections complete: **4 / 4**
- Blockers: none (in-scope)

### What landed

| Area | Change |
|------|--------|
| Grok provider | Catalog/auth isolation, Grok-safe chat request (no Local-only fields), settings/UX polish |
| SSE fidelity | `cancelled` / `streamError` / `incompleteToolCallCount`; SSE errors throw; incomplete tools not silent |
| Agent loop | Pure `decideAgentLoop`; no silent empty success; retries then explicit error; SessionLog terminal reasons |
| Tests | AgentLoopDecision/Control, GabSseAccumulator, ModelCatalog, GabClientJson Grok contracts |

## CEO satisfaction checklist

- [x] All sections in `plan.md` are `done`
- [x] Success criteria above are met
- [x] Project test suite green for scoped packages (full suite OOS fails documented)
- [x] Human has not objected in this round

**CEO final status:** done

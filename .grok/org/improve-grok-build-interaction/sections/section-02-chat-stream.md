# Section — Chat streaming residual polish

**Section ID:** section-02-chat-stream  
**Project:** improve-grok-build-interaction  
**Plan:** `.grok/org/improve-grok-build-interaction/plan.md`  
**Status:** done  
**Manager:** section-02-chat-stream (checkpoint iteration-01 → SECTION_DONE)

## Goal (from Director)

Ensure OpenAI-compatible **chat streaming** on Grok Build (cli-chat-proxy) and Local LLM chat path shows correct content **once** (no runaway repeat / memory spike), surfaces stream/API failures cleanly, and preserves Stop/abort. Build on `StreamContentMerger` / `StreamUiCoalescer` / `GabSseAccumulator` — **verify gaps**, do not redesign without evidence.

## Acceptance criteria

- [x] Snapshot-safe merge and UI coalesce remain correct; no regression in existing merger/coalescer tests
- [x] GROK_BUILD chat completions keep required cli-chat-proxy headers (token auth, client version/surface, model override when set)
- [x] Stream errors / non-2xx responses fail the turn with a clear message (not empty “success”)
- [x] Stop continues to abort active SSE body and unblock the worker (no hang on next chunk)
- [x] Any residual GROK_BUILD-specific stream quirks (if found) fixed with pure helpers + unit tests
- [x] No quadratic append path reintroduced; safety cap behavior retained
- [x] Scoped tests green for client/chat stream packages touched

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `GabClient` chat stream + abort + provider auth headers
- `GabSseAccumulator`, `StreamContentMerger`
- `StreamUiCoalescer` (only if residual thrash found)
- `AgentSession` stream callbacks / iteration body reset **only if** GROK_BUILD chat agent path still misbehaves (do not redo full loop taxonomy from prior project)
- Wire-up in tool window stream flush **only** if required for a proven bug
- Unit tests: merger, accumulator, any new pure stream helpers
- Actionable GROK_BUILD chat failure formatting (pure helper + chat-path wire)

**Out of scope:**

- Full reimplementation of `StreamContentMerger` rules without a failing test case
- Local LLM `/api/agent` poll loop (section-03)
- Auth.json parsing (section-01)
- Workbench badges / Apply UX (section-04)
- Packaging/version (section-05)

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Client | `src/main/kotlin/com/waryway/gab/client/GabClient.kt` |
| SSE | `src/main/kotlin/com/waryway/gab/client/GabSseAccumulator.kt` |
| Merge | `src/main/kotlin/com/waryway/gab/client/StreamContentMerger.kt` |
| UI coalesce | `src/main/kotlin/com/waryway/gab/ui/StreamUiCoalescer.kt` |
| Chat agent | `src/main/kotlin/com/waryway/gab/chat/AgentSession.kt` (stream iteration only if needed) |
| Failure UX | new pure helper under `client/` or `ui/` + chat error branch in `WarywayGabToolWindowPanel.kt` |
| Tests | `src/test/kotlin/com/waryway/gab/client/StreamContentMergerTest.kt`, `GabSseAccumulatorTest.kt`, `GabClientJsonTest.kt`, `src/test/kotlin/com/waryway/gab/ui/StreamUiCoalescerTest.kt` |

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/improve-grok-build-interaction/iterations/section-02-chat-stream/iteration-01.md` | done | SECTION_DONE — wo-02-01 + wo-02-02; verifier 268/268 |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `.grok/org/improve-grok-build-interaction/iterations/section-02-chat-stream/iteration-01.md`
- `.grok/org/improve-grok-build-interaction/work-orders/wo-02-01.md`
- `.grok/org/improve-grok-build-interaction/work-orders/wo-02-02.md`
- `.grok/org/improve-grok-build-interaction/delegations/workers-queue-section-02.json`
- `.grok/org/improve-grok-build-interaction/delegations/verifier-report-iter-01.md`

**Director sign-off:** done (2026-07-17 verify)

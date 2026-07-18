# Iteration 01 — AgentSession premature-exit fixes

**Section:** `.grok/org/grok-support-agent-stability/sections/section-03-agent-loop-stability.md`  
**Iteration:** 01 of 3  
**Status:** done

## This iteration focus

- [x] Extract pure loop-decision helper (Continue / TerminalSuccess / TerminalError / Cancelled / AmbiguousRetry) testable without IDE
- [x] Rewire `AgentSession.run` so exit only on cancel, hard error, clean model stop with no tools, or max iterations — never silent empty success
- [x] Bounded retry for empty/ambiguous completions; log terminal reason via `SessionLog` on every exit path
- [x] Cancel after stream/partial still returns “Stopped by user”; max-iterations remains explicit in final content
- [x] Unit tests for decision taxonomy; compile/test green for touched packages

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-03-01 | `.grok/org/grok-support-agent-stability/work-orders/wo-03-01.md` | implementer | done | `AgentLoopDecision.kt` + `AgentSession` rewire; SessionLog terminal reasons |
| wo-03-02 | `.grok/org/grok-support-agent-stability/work-orders/wo-03-02.md` | tester | done | `AgentLoopDecisionTest` matrix + section-02 field wiring in AgentSession |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Command | `.\gradlew.bat test --tests "com.waryway.gab.chat.AgentLoopDecision*"` then full `.\gradlew.bat test` if scoped pass |
| Result | **pass** (Manager checkpoint): `AgentLoopDecision*` BUILD SUCCESSFUL. Full suite: 2 unrelated failures outside `com.waryway.gab.chat` (CEO note). |

## Results

**Outcome:** pass — core premature-exit fix landed in iteration 01  

**Files changed:**

- `src/main/kotlin/com/waryway/gab/chat/AgentLoopDecision.kt` (new)
- `src/main/kotlin/com/waryway/gab/chat/AgentSession.kt` (rewired exit policy + section-02 field consumption)
- `src/test/kotlin/com/waryway/gab/chat/AgentLoopDecisionTest.kt` (new)

**Tests:**

- `.\gradlew.bat test --tests "com.waryway.gab.chat.AgentLoopDecision*"` → BUILD SUCCESSFUL
- Coverage: cancel, tools CONTINUE, incomplete tool_calls retry/error, stop/null success, empty_ambiguous retry+error, length, content_filter, max_iterations, MCP-off single-shot

## Manager notes

What worked:

- Pure `decideAgentLoop` taxonomy cleanly separates policy from IDE/`Project` — section-04 can reuse without full session mocks.
- Policy table implemented as specified: tools always CONTINUE; empty/ambiguous → up to 2 retries then TERMINAL_ERROR; cancel pre/post-stream and mid-tools → `"Stopped by user."` + `agent terminal reason=cancel`.
- Section-02 fields (`cancelled`, `streamError`, `incompleteToolCallCount`) wired so partial/error streams cannot look like clean success.
- UI left untouched: `result.finalContent` surfaces terminal text; `finally` still re-enables Send / disables Stop.

Carry-forward (non-blocking for this section):

- Full suite still has 2 failures outside chat package — owned by section-04 / other sections, not this loop.
- `AgentLoopAction.MAX_ITERATIONS` in the helper is mostly defensive (`iteration > max`); primary max-iter path is the post-`while` appendix in `AgentSession` — intentional and user-visible.
- Empty content + explicit `finishReason=stop` is TERMINAL_SUCCESS with empty body → caller may show `(no response)`; ambiguous null/blank finish is the retry path.

## Remaining gaps

- None for section-03 acceptance criteria.
- Soft follow-ups outside scope: session-level integration tests (section-04), LocalLlmAgentSession cancel parity (out of scope).

## Next iteration focus (if not done)

- N/A — section complete in iteration 01.

**Manager verdict:** SECTION_DONE

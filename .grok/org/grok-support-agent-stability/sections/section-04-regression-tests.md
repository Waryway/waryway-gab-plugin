# Section — Regression tests for Grok + agent stability

**Section ID:** section-04-regression-tests  
**Project:** grok-support-agent-stability  
**Plan:** `.grok/org/grok-support-agent-stability/plan.md`  
**Status:** done  
**Manager:** manager-section-04 (checkpoint → SECTION_DONE)

## Goal (from Director)

Lock in Grok provider contracts and agent-loop/SSE premature-stop fixes with **focused unit tests**. Ensure the project test suite is green so CEO can satisfy “tests must pass before done.”

## Acceptance criteria

- [x] Tests cover agent **loop control taxonomy**: continue on tool_calls; stop on clean stop; empty/ambiguous response policy; max iterations; cancel — `AgentLoopControlTest` (15 tests, wo-04-01-01)
- [x] Tests cover SSE: empty stream, error event → non-silent failure, finish_reason variants, incomplete tool_call builders — `GabSseAccumulatorTest` (wo-04-01-02)
- [x] Tests cover Grok catalog/provider selection and any Grok-specific request construction assertions from section-01 — `ModelCatalogTest` + `GabClientJsonTest` (wo-04-01-03)
- [x] No dependency on live xAI/Gab network in unit tests (fixtures / pure functions / fake client)
- [x] `./gradlew test` green on Windows for **in-scope** chat/client/model suites; full suite: 176 completed, 2 failed **OUT OF SCOPE** (`InputNormalizerTest`, `GolandMcpExecutorTest` — pre-existing, not this project)
- [x] Existing tests remain green (ModelCatalog, GabSseAccumulator, LocalLlmAgentSession attachments, etc.) for in-scope packages

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- New/extended tests under `src/test/kotlin/com/waryway/gab/`
- Minimal production extractions **only if required for testability** (prefer using helpers introduced by sections 02–03)
- Gradle test run verification

**Out of scope:**

- Integration tests against real xAI API keys
- UI screenshot / EDT tests beyond existing style
- Rewriting unrelated test suites
- Pre-existing failures: `InputNormalizerTest`, `GolandMcpExecutorTest`

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Agent tests | `src/test/kotlin/com/waryway/gab/chat/AgentLoopControlTest.kt` |
| SSE tests | `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt` |
| Client JSON | `src/test/kotlin/com/waryway/gab/client/GabClientJsonTest.kt` |
| Catalog | `src/test/kotlin/com/waryway/gab/model/ModelCatalogTest.kt` |
| Production under test | `AgentSession.kt`, `GabSseAccumulator.kt`, `GabClient.kt`, `ModelCatalog.kt` |

## Suggested test matrix (Manager may refine)

| Case | Expected |
|------|----------|
| Response with tool_calls + finish tool_calls | continue loop / decision = Continue |
| Response stop + content, no tools | TerminalSuccess |
| Empty content, no tools, null finish | policy from section-03 (retry/error — assert implemented behavior) |
| Cancelled flag true | TerminalCancelled / “Stopped by user” |
| Max iterations | message includes stop-after-N |
| SSE error event | fails or non-empty error result |
| Incomplete tool builder at end | not silent empty tools without signal |
| Grok default model + belongsToProvider | catalog assertions |
| buildJsonChatRequest GROK | tools/stream shape valid OpenAI JSON |

## Soft dependency

Prefer running after or alongside sections 01–03 so tests match final APIs. If concert-spawned early, write tests against **contracts in section tickets** and adjust once implementations land (iteration 2).

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/grok-support-agent-stability/iterations/section-04-regression-tests/iteration-01.md` | done | All 3 WOs done; in-scope suites green; full suite 2 OOS fails only |

## Director verification

- [x] All acceptance criteria checked (in-scope; OOS suite fails documented)
- [x] Manager returned `done` (`SIGNAL: SECTION_DONE`)
- [x] Artifacts listed below exist

**Artifacts:**

- `.grok/org/grok-support-agent-stability/iterations/section-04-regression-tests/iteration-01.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-04-01-01.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-04-01-02.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-04-01-03.md`
- `src/test/kotlin/com/waryway/gab/chat/AgentLoopControlTest.kt`
- `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt` (extended)
- `src/test/kotlin/com/waryway/gab/model/ModelCatalogTest.kt` (extended)
- `src/test/kotlin/com/waryway/gab/client/GabClientJsonTest.kt` (extended)

**Director sign-off:** done (Director MODE=verify 2026-07-17)

## Checkpoint note (Manager)

Only remaining full-suite failures are **out of scope** for grok-support-agent-stability:

1. `InputNormalizerTest`
2. `GolandMcpExecutorTest`

Both pre-existing and unrelated to chat/client/model Grok + agent stability work. Section acceptance met without further iteration.

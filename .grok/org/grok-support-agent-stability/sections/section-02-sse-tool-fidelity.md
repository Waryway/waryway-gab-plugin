# Section — SSE / tool-call / finish-reason fidelity

**Section ID:** section-02-sse-tool-fidelity  
**Project:** grok-support-agent-stability  
**Plan:** `.grok/org/grok-support-agent-stability/plan.md`  
**Status:** done  
**Manager:** manager (proxy / CEO concert)

## Goal (from Director)

Harden OpenAI-compatible **SSE streaming** so tool-call fragments, finish reasons, and stream errors are faithfully recovered. Premature agent stops often start here: dropped incomplete `tool_calls`, null finish reasons treated as normal end, or server error events that become empty “successful” completions.

## Acceptance criteria

- [x] Fragmented streamed tool calls still yield complete `ToolCall`s when id/name/args arrive across chunks (existing case preserved; edge cases fixed)
- [x] Incomplete tool builders (missing id/name at stream end) are **not silently discarded without signal** — either recovered, or result flags incomplete tools / error so the agent loop can react
- [x] `finish_reason` from final chunks (including `tool_calls`, `stop`, `length`, null) is captured consistently; `null` vs `"stop"` distinguishable in `ChatCompletionResult`
- [x] SSE `error` events fail the completion (or return a typed error path) instead of empty success — no silent end-of-turn on inference failure
- [x] Mid-stream cancel does not look like a clean model `stop` with partial text as the only signal (cancel flag / empty partial handling documented and implemented in client boundary)
- [x] Unit tests in `GabSseAccumulatorTest` (and related) cover empty stream, error event, finish_reason variants, incomplete tool_calls

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `GabSseAccumulator` (primary)
- `GabClient.chatCompletionStreaming` error/cancel/result mapping
- Non-stream parse paths in `GabClient` if shared finish/tool helpers change
- SSE unit tests

**Out of scope:**

- `AgentSession` loop policy (section-03) — expose clear result fields; do not reimplement the agent loop here unless a one-line handoff is required
- Grok model catalog/settings (section-01)
- Local LLM agent polling

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| SSE | `src/main/kotlin/com/waryway/gab/client/GabSseAccumulator.kt` |
| Client stream | `src/main/kotlin/com/waryway/gab/client/GabClient.kt` |
| Models | `src/main/kotlin/com/waryway/gab/model/GabModels.kt` (if result types need extension) |
| Tests | `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt` |
| Tests | `src/test/kotlin/com/waryway/gab/client/GabClientJsonTest.kt` |

## Investigation prompts for Manager

1. When xAI/Gab streams tool_calls without `id` on later deltas only — is first chunk always present? What if first chunk is lost?
2. Does `toToolCall()` drop builders with blank id → zero tools → agent exits?
3. Does processSseLine swallow JSON parse failures (`runCatching` empty)?
4. After SSE error event, does `toResult()` still return empty content with null finishReason?
5. Is `finish_reason` ever nested differently (e.g. only on choice object without delta)?

## Known failure modes (Director)

| Symptom | Likely cause |
|---------|----------------|
| Agent stops after “thinking” with little/no text | Empty stream + null finish → AgentSession break |
| Agent stops when it should call tools | Incomplete tool builders dropped |
| New message works immediately | Loop exited cleanly; not hung |
| Log shows finish_reason=null or missing | Stream ended without final chunk / cancel |

## Manager prep notes (iteration 01)

Confirmed from code:

- `ToolCallBuilder.toToolCall()` returns `null` when `id` or `name` blank → dropped from `toolCalls` with no signal.
- `GabClient.chatCompletionStreaming` logs `SseEvent.Error` then still returns `accumulator.toResult()` (empty “success”).
- Mid-stream `cancelled()` breaks the read loop and returns partial result with no cancel flag.
- `extractFinishReason` only matches quoted string values; JSON `null` stays field-null (good) but is indistinguishable from “never received finish chunk” without other signals.
- Existing tests: content accumulate, error event surface, happy-path fragmented tools — missing empty stream, incomplete tools, finish variants, client error/cancel mapping.

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/grok-support-agent-stability/iterations/section-02-sse-tool-fidelity/iteration-01.md` | done | AC met; scoped client tests green; full suite 2 unrelated fails |

## Checkpoint notes (iteration 01)

**Workers:**

- **wo-s02-01-01** (done): `ChatCompletionResult` + `cancelled` / `streamError` / `incompleteToolCallCount`; SSE error throws `GabApiException`; incomplete tools counted; cancel no fabricated stop.
- **wo-s02-01-02** (done): `GabSseAccumulatorTest` expanded (~23 cases) covering empty stream, error/`streamError`, finish_reason variants, incomplete tools, cancel mapping, fragmented happy path.

**Verifier (manager-run scoped):**

```text
.\gradlew.bat test --tests com.waryway.gab.client.GabSseAccumulatorTest --tests com.waryway.gab.client.GabClientJsonTest
→ BUILD SUCCESSFUL
```

CEO note: full suite has 2 unrelated failures; client package tests green. AgentSession loop policy untouched (section-03 handoff via result fields only).

**Manager verdict:** SECTION_DONE — all acceptance criteria satisfied in one iteration.

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `src/main/kotlin/com/waryway/gab/client/GabClient.kt` — `ChatCompletionResult` fields; stream error throw; cancel mapping
- `src/main/kotlin/com/waryway/gab/client/GabSseAccumulator.kt` — incomplete count, streamError, finish_reason fidelity
- `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt` — edge-case suite
- `.grok/org/grok-support-agent-stability/work-orders/wo-s02-01-01.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-s02-01-02.md`
- `.grok/org/grok-support-agent-stability/iterations/section-02-sse-tool-fidelity/iteration-01.md`

**Director sign-off:** done (Director MODE=verify 2026-07-17)

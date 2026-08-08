# Section — AgentSession premature-exit fixes

**Section ID:** section-03-agent-loop-stability  
**Project:** grok-support-agent-stability  
**Plan:** `.grok/org/grok-support-agent-stability/plan.md`  
**Status:** done  
**Manager:** manager-section-03 (proxy)

## Goal (from Director)

Fix the cloud multi-step **agent loop** (`AgentSession`) so it does **not** terminate mid-processing without a clear terminal reason. Human observation: the process stops mid-work but is **not stuck** — a new message can be sent. Target terminal conditions only:

1. User cancel  
2. Hard error with visible message  
3. Model terminal finish (`stop` / equivalent) with **no** pending/incomplete tool calls  
4. Max iterations with an explicit message  

## Acceptance criteria

- [x] Loop continues when `toolCalls` is non-empty (existing) **and** when finish_reason indicates tool use / pending tools even if partial recovery improved by section-02
- [x] Empty content + empty tools + non-terminal/null finish does **not** silently end as a successful short turn — bounded retry and/or explicit error/system message (document policy)
- [x] User cancel always surfaces “Stopped by user” (or equivalent), including cancel during stream/tools — not a partial assistant answer as if complete
- [x] Terminal reason is logged via `SessionLog` on every exit path (`cancel`, `error`, `stop`, `tool_rounds_exhausted`, `max_iterations`, etc.)
- [x] Max-iterations path remains explicit in final content; no silent stop at hard cap
- [x] UI path (`WarywayGabToolWindowPanel` AgentSession branch) still re-enables Send and does not leave Stop stuck; no regression to “stuck forever”
- [x] Unit-testable loop control (extract pure decision helper if needed so section-04 can assert without full IDE)

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `AgentSession.kt` (primary) — iteration loop, exit conditions, status/log messages
- Small helpers extracted for testability (e.g. `shouldContinueAgentLoop(response, cancelled, iteration)`)
- Minimal UI/status wiring if terminal reasons must surface in the agent turn transcript
- Coordination with `ChatCompletionResult` fields from section-02

**Out of scope:**

- Deep SSE parsing internals (section-02 owns) — consume improved result API
- Local LLM `LocalLlmAgentSession` / `AgentClient` unless shared cancel UX is broken for Grok path
- Raising `MAX_ITERATIONS` without product reason (if raised, document; prefer clear messaging)
- Full redesign of conversation manager

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Agent loop | `src/main/kotlin/com/waryway/gab/chat/AgentSession.kt` |
| Loop decision | `src/main/kotlin/com/waryway/gab/chat/AgentLoopDecision.kt` |
| Client result type | `src/main/kotlin/com/waryway/gab/client/GabClient.kt` |
| UI send path | `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` (AgentSession branch ~697–753) |
| Session log | `src/main/kotlin/com/waryway/gab/diagnostics/SessionLog.kt` |
| Tools | `src/main/kotlin/com/waryway/gab/tools/GolandMcpExecutor.kt` (only if tool errors abort loop incorrectly) |
| Tests | `src/test/kotlin/com/waryway/gab/chat/AgentLoopDecisionTest.kt` |

## Current loop sketch (Director — for workers)

```text
while iterations < MAX:
  if cancelled → return "Stopped by user"
  response = chatCompletion(...)
  if toolCalls not empty → execute tools → continue
  // else: take content, break   ← premature if empty / incomplete tools dropped / error
if max → append max-iter note
return finalContent or "(no response)"
```

**Required design change (intent, not prescription):** classify response into Continue / TerminalSuccess / TerminalError / Cancelled before `break`; never treat “no tools + empty content + null finish” as success without a policy (retry N times or error).

## Investigation prompts for Manager

1. Reproduce empty stream → `(no response)` or blank completeAgentTurn.
2. If MCP tools off, does single-shot chat still work? (must remain OK)
3. When tool execution throws, does the loop die without message?
4. `runBlocking` + background Thread: cancel flag race — is `cancelled` checked after stream returns partial?
5. finishReason `"length"`: should we continue, warn, or stop with message?

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/grok-support-agent-stability/iterations/section-03-agent-loop-stability/iteration-01.md` | done | AC met; AgentLoopDecision* green; core premature-exit fix |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `src/main/kotlin/com/waryway/gab/chat/AgentLoopDecision.kt` — pure `decideAgentLoop` taxonomy
- `src/main/kotlin/com/waryway/gab/chat/AgentSession.kt` — rewired exit policy; cancel/streamError/incomplete tools
- `src/test/kotlin/com/waryway/gab/chat/AgentLoopDecisionTest.kt` — decision matrix unit tests
- Work orders wo-03-01, wo-03-02 done
- Iteration-01 done; Manager checkpoint `AgentLoopDecision*` BUILD SUCCESSFUL

**Director sign-off:** done (Director MODE=verify 2026-07-17)

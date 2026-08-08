# Plan — Grok support & agentic loop stability

**Project slug:** grok-support-agent-stability  
**Directive:** `.grok/org/grok-support-agent-stability/directive.md`  
**Status:** done  
**Director round:** 1 (verify)

## Executive summary

Deepen first-class **Grok (xAI)** support in the existing OpenAI-compatible client path (`ModelProvider.GROK` → `https://api.x.ai/v1`), and stop the cloud **AgentSession** multi-step tool loop from ending mid-task without a clear terminal reason. Work is split into four independently verifiable sections: Grok provider path, SSE/tool-call fidelity, agent-loop exit control, and regression tests. No unrelated refactors; scope is `waryway-gab-plugin` only.

## Discovery notes (Director)

### Grok path (existing)

| Piece | Location | Notes |
|-------|----------|-------|
| Provider enum | `src/main/kotlin/com/waryway/gab/model/ModelProvider.kt` | `GROK` → `https://api.x.ai/v1`, console key help |
| Catalog | `src/main/kotlin/com/waryway/gab/model/ModelCatalog.kt` | Default `grok-4.3`; fallbacks 4.2/4/3/build-0.1 |
| Settings/UX | `WarywayGabSettings.kt`, `WarywayGabConfigurable.kt` | Per-provider key, default model, Test Connection |
| Client | `GabClient.kt` | Shared OpenAI-compatible SSE + tools; little Grok-specific branching |
| Cloud agent loop | `AgentSession.kt` | Used for Grok/Gab (and Local LLM chat-only) |

### Premature-stop suspects (cloud agent path)

1. **`AgentSession.run`** (`MAX_ITERATIONS = 20`): after a completion with **no tool calls**, always `break` — even on empty content, null `finishReason`, or ambiguous stream end. No retry; no user-visible terminal reason except max-iter message.
2. **`GabSseAccumulator`**: incomplete tool builders (blank `id` or `name`) are **dropped** → loop sees zero tools and exits while the model intended tool use.
3. **SSE errors**: `SseEvent.Error` is logged only; stream may finish as empty “success” and end the agent turn.
4. **Cancel mid-stream**: partial accumulator returned; next loop iteration may exit as a short final answer rather than “Stopped by user.”
5. **Finish reasons**: only soft-check for `"tool_calls"`; other xAI/OpenAI values (`stop`, `length`, `content_filter`, null) are not classified or surfaced.
6. **No unit tests** for `AgentSession` loop control (existing tests cover attachments, SSE happy-path, model catalog).

Local LLM server `/api/agent` (`LocalLlmAgentSession`) is **out of primary scope** unless a shared cancel/status bug is found; human report matches cloud multi-step loop behavior (new message still works).

## Major phases

| Phase | Section ID | Title | Owner | Status | Depends on |
|-------|------------|-------|-------|--------|------------|
| 1 | section-01-grok-provider | First-class Grok (xAI) provider path | Manager | done | — |
| 1 | section-02-sse-tool-fidelity | SSE / tool-call / finish-reason fidelity | Manager | done | — |
| 1 | section-03-agent-loop-stability | AgentSession premature-exit fixes | Manager | done | soft: 02 |
| 2 | section-04-regression-tests | Regression tests Grok + loop stability | Manager | done | soft: 01–03 |

## Section roster

### section-01-grok-provider — First-class Grok (xAI) provider path

- **Path:** `.grok/org/grok-support-agent-stability/sections/section-01-grok-provider.md`
- **Goal:** Make Grok a reliable default cloud path: catalog, auth, request shape, settings/UX, and any xAI-specific client gaps vs Gab.
- **Acceptance:** Grok models/keys/base URL correct; request path documented/tested; no silent wrong-provider routing; settings/test-connection remain usable.
- **Manager playbook:** `general`
- **Status:** done

### section-02-sse-tool-fidelity — SSE / tool-call / finish-reason fidelity

- **Path:** `.grok/org/grok-support-agent-stability/sections/section-02-sse-tool-fidelity.md`
- **Goal:** Ensure streamed completions (esp. Grok/xAI) correctly accumulate content, tool_calls, finish reasons, and surface stream errors instead of empty “success.”
- **Acceptance:** Fragmented/incomplete tool deltas and finish reasons handled; stream errors propagate; empty/partial streams distinguishable from clean end_turn/stop.
- **Manager playbook:** `general`
- **Status:** done

### section-03-agent-loop-stability — AgentSession premature-exit fixes

- **Path:** `.grok/org/grok-support-agent-stability/sections/section-03-agent-loop-stability.md`
- **Goal:** Multi-step agent loop exits only for clear terminal reasons (user cancel, hard error with message, model stop with no pending tools, max iterations with message).
- **Acceptance:** Empty/ambiguous responses do not silently end work; terminal reason logged + user-visible when non-success; tool rounds continue when tools pending; regression-friendly loop control.
- **Manager playbook:** `general`
- **Status:** done

### section-04-regression-tests — Regression tests for Grok + agent stability

- **Path:** `.grok/org/grok-support-agent-stability/sections/section-04-regression-tests.md`
- **Goal:** Add focused unit tests for Grok catalog/request contracts and agent-loop/SSE premature-stop scenarios; keep suite green.
- **Acceptance:** New tests cover loop exit taxonomy, empty stream, tool continuation, finish reasons; `./gradlew test` green (or documented scoped filter).
- **Manager playbook:** `general`
- **Status:** done

## Risks & assumptions

- **Assumption:** Premature stops are primarily on the **cloud** `AgentSession` + `GabClient` path (Grok/Gab), not Local LLM `/api/agent` polling.
- **Risk:** xAI streaming tool-call JSON shape may differ slightly from Gab; fix must stay OpenAI-compatible without breaking Gab.
- **Risk:** Over-aggressive empty-response retries could burn tokens — need bounded retries and clear logging.
- **Risk:** Hardening loop control without fixture-based tests is fragile — section-04 is required for CEO sign-off.
- **Constraint:** No unrelated refactors; no multi-repo work.

## Recommended manager order (concert-friendly)

All four sections may be concert-spawned. Preferred priority if sequential:

1. **02** + **03** (stability) — highest user pain  
2. **01** (Grok polish)  
3. **04** (tests; reuses contracts from 01–03)

Soft dependency: 03 benefits from 02’s finish/tool fidelity; 04 should absorb contracts landed by 01–03 (coordinate via section tickets / work orders, not blocking spawn).

## Director verification log

| Round | Sections done | Blockers | Notes |
|-------|---------------|----------|-------|
| 0 | 0 / 4 | — | Plan drafted; managers-queue filled |
| 1 | 4 / 4 | — | MODE=verify: all Managers SECTION_DONE (iter 01). Code review of landed artifacts vs AC: Grok provider identity + catalog + createClient key isolation; SSE incomplete/error/cancel fidelity; AgentLoopDecision + AgentSession exit policy; regression suites AgentLoopControlTest / GabSseAccumulatorTest / ModelCatalogTest / GabClientJsonTest. CEO: scoped chat/client/model BUILD SUCCESSFUL; full suite 176 tests, 2 failed OOS (InputNormalizerTest, GolandMcpExecutorTest — pre-existing). No remaining in-scope gaps. |

**Director sign-off:** done

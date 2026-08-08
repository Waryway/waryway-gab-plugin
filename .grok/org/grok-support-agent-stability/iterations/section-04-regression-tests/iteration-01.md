# Iteration 01 — Regression tests for Grok + agent stability

**Section:** `.grok/org/grok-support-agent-stability/sections/section-04-regression-tests.md`  
**Iteration:** 01 of 3  
**Status:** done

## This iteration focus

- [x] Add unit tests for **agent loop control taxonomy** (continue / terminal success / empty-ambiguous policy / cancel / max iterations) against section-03 contracts or a minimal pure decision helper
- [x] Extend **SSE** tests for empty stream, error events (non-silent), finish_reason variants, incomplete tool_call builders
- [x] Lock **Grok catalog/provider** and GROK `buildJsonChatRequest` request-shape contracts from section-01
- [x] Keep suite offline (fixtures / pure functions / fake client only) and land `./gradlew test` green on Windows (in-scope suites)

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-04-01-01 | `.grok/org/grok-support-agent-stability/work-orders/wo-04-01-01.md` | tester | done | `AgentLoopControlTest` — 15/15 green; pure `decideAgentLoop` taxonomy |
| wo-04-01-02 | `.grok/org/grok-support-agent-stability/work-orders/wo-04-01-02.md` | tester | done | `GabSseAccumulatorTest` premature-stop locks; 32 SSE/JSON scoped green |
| wo-04-01-03 | `.grok/org/grok-support-agent-stability/work-orders/wo-04-01-03.md` | tester | done | `ModelCatalogTest` + `GabClientJsonTest` Grok contracts; 25 green |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Command | `./gradlew test` (repo root, Windows OK) |
| Outcome | CEO full suite: **176 completed, 2 failed** — failures OUT OF SCOPE (pre-existing) |

## Results

**Outcome:** pass (section criteria met; only out-of-scope failures remain)  
**Files changed:**

- `src/test/kotlin/com/waryway/gab/chat/AgentLoopControlTest.kt` (new)
- `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt`
- `src/test/kotlin/com/waryway/gab/model/ModelCatalogTest.kt`
- `src/test/kotlin/com/waryway/gab/client/GabClientJsonTest.kt`

**Tests:**

| Suite | Result |
|-------|--------|
| `com.waryway.gab.chat.AgentLoopControlTest` | 15/15 green |
| `GabSseAccumulatorTest` (+ related client JSON) | scoped green (32 with GabClientJsonTest per wo-04-01-02) |
| `ModelCatalogTest` + `GabClientJsonTest` | 25 green (wo-04-01-03) |
| Full `./gradlew test` (CEO) | 176 completed; **2 failed OUT OF SCOPE**: `InputNormalizerTest`, `GolandMcpExecutorTest` (pre-existing; not chat/client/model / this project) |

All chat / client / model regression tests for **grok-support-agent-stability** are green. No live network in unit tests.

## Manager notes

- All three tester work orders completed in iteration 01 with no production blockers.
- wo-04-01-01 used pure section-03 API (`decideAgentLoop` / `AgentLoopAction`) — no production extraction needed.
- wo-04-01-02 coordinated with section-02 base matrix; added complementary premature-stop locks only.
- wo-04-01-03 extended catalog + GROK request-shape without deleting Gab/Local coverage.
- Verifier: CEO full `./gradlew test` used as build-test verification. In-scope suites pass. The two remaining failures (`InputNormalizerTest`, `GolandMcpExecutorTest`) are **out of scope** for this section and project (pre-existing / unrelated packages).
- Acceptance criteria for this section are met; no iteration 02 required.

## Remaining gaps

- None for section-04 acceptance.
- **Note (out of scope):** full suite still has 2 pre-existing reds: `InputNormalizerTest`, `GolandMcpExecutorTest` — not owned by grok-support-agent-stability / section-04.

## Next iteration focus (if not done)

- N/A — section done

**Manager verdict:** SECTION_DONE — all in-scope acceptance met; only out-of-scope suite failures remain

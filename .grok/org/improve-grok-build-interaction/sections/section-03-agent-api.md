# Section — Local LLM `/api/agent` terminal fidelity

**Section ID:** section-03-agent-api  
**Project:** improve-grok-build-interaction  
**Plan:** `.grok/org/improve-grok-build-interaction/plan.md`  
**Status:** done  
**Manager:** manager-section-03-agent-api

## Goal (from Director)

Make the Local LLM **agent path** (`AgentClient` + `LocalLlmAgentSession` → `/api/agent`) complete or fail with a **clear terminal reason**, keep Stop/cancel reliable, and surface dry-run vs APPLY / tool progress without silent mid-run death. Prefer pure helpers + unit tests over brittle UI tests.

## Acceptance criteria

- [x] Terminal states (`done`, `failed`, `cancelled`, user stop, timeout) always produce non-blank user-visible final content and sensible status strings
- [x] `cancelActiveRun` / Stop path posts cancel and ends the poll loop without leaving the UI stuck “running”
- [x] Timeout cancels the server run and throws/surfaces a distinct timeout message (not a generic connection error)
- [x] dryRun=true vs false reflected in status and final badge text (existing behavior preserved or improved)
- [x] Empty `finalAnswer` + non-error terminal does not look like success with blank bubble
- [x] Unit tests cover pure finish/status formatting and cancel/timeout edge cases (mockable client or extracted pure functions)
- [x] No routing of GROK_BUILD or cloud providers into `/api/agent` (agent mode remains LOCAL_LLM-only)

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `LocalLlmAgentSession.kt` (poll loop, finish content, cancel, timeout)
- `AgentClient.kt` (start/get/cancel parse robustness if bugs found)
- Pure helpers extracted for finish/status if needed for testability
- Unit tests: expand `LocalLlmAgentSessionTest`, `AgentClientTest`
- Light tool-window glue only if cancel/status wiring is broken

**Out of scope:**

- Cloud `AgentSession` multi-tool loop redesign (done under `grok-support-agent-stability`)
- Grok Build OIDC auth (section-01)
- StreamContentMerger (section-02)
- Workbench Swing layout redesign (section-04) — only agent-path contracts here
- Version bump (section-05)

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Agent session | `src/main/kotlin/com/waryway/gab/chat/LocalLlmAgentSession.kt` |
| Agent HTTP | `src/main/kotlin/com/waryway/gab/client/AgentClient.kt` |
| Tool window glue | `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` (`runLocalLlmAgent` / Stop only if needed) |
| Tests | `src/test/kotlin/com/waryway/gab/chat/LocalLlmAgentSessionTest.kt`, `src/test/kotlin/com/waryway/gab/client/AgentClientTest.kt` |
| Context | `HOW_TO_LOAD_IN_IDE.md` (agent vs chat table — read-only unless copy wrong) |

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `iterations/section-03-agent-api/iteration-01.md` | done | wo-03-01 + wo-03-02 done; verifier 268 green; all ACs met |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `src/main/kotlin/com/waryway/gab/chat/LocalLlmAgentSession.kt` — pure terminal format helpers; `AgentRunOps` poll seam; cancel/timeout clear `activeRunId`
- `src/test/kotlin/com/waryway/gab/chat/LocalLlmAgentSessionTest.kt` — 35 tests (format + cancel/timeout FakeAgentOps)
- `src/main/kotlin/com/waryway/gab/client/AgentClient.kt` — LOCAL_LLM `/api/agent` client (unchanged routing)
- `src/test/kotlin/com/waryway/gab/client/AgentClientTest.kt` — 12 tests green
- `.grok/org/improve-grok-build-interaction/work-orders/wo-03-01.md` (done)
- `.grok/org/improve-grok-build-interaction/work-orders/wo-03-02.md` (done)
- `.grok/org/improve-grok-build-interaction/iterations/section-03-agent-api/iteration-01.md` (done)
- `.grok/org/improve-grok-build-interaction/delegations/verifier-report-iter-01.md` (268/268 PASS)

**Director sign-off:** done (2026-07-17 verify)

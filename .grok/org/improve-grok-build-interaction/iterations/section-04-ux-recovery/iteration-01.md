# Iteration 01 — Workbench / send UX & recovery copy

**Section:** `.grok/org/improve-grok-build-interaction/sections/section-04-ux-recovery.md`  
**Iteration:** 1 of 3  
**Status:** done

## This iteration focus

- [x] Pure recovery/format helpers for Grok Build send failures (reuse section-01 auth strings if present; do not fork catalogs)
- [x] Tool-window Send catch paths use pure formatters for GROK_BUILD (no bare `Error: ${e.message}` for auth/proxy classes)
- [x] Send path labels stay correct for Local LLM (`Chat` | `Agent · dry-run` | `Agent · APPLY`); workbench never claims agent mode for non–Local-LLM
- [x] Optional light Grok Build session status affordance (badge / system line / refresh) without full provider re-select
- [x] Unit tests for all new/changed pure UX helpers

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-04-01 | `.grok/org/improve-grok-build-interaction/work-orders/wo-04-01.md` | implementer | done | Pure `GrokBuildSendUx` + tests; auth via `GrokBuildAuthRecovery` |
| wo-04-02 | `.grok/org/improve-grok-build-interaction/work-orders/wo-04-02.md` | implementer | done | Tool window formatters, badges, session chip; workbench Local-only |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Command | scoped packages `client.*` `chat.*` `ui.*` `model.*` (report: `delegations/verifier-report-iter-01.md`) |
| Result | **PASS** — 268/268, 0 fail |

## Results

**Outcome:** SECTION_DONE — all section acceptance criteria met in iteration 01.

**Files changed (workers):**

- `src/main/kotlin/com/waryway/gab/ui/GrokBuildSendUx.kt` (new)
- `src/main/kotlin/com/waryway/gab/ui/GrokBuildChatFailureUx.kt` (facade; used by tool window)
- `src/test/kotlin/com/waryway/gab/ui/GrokBuildSendUxTest.kt` (new — 20 tests)
- `src/test/kotlin/com/waryway/gab/ui/GrokBuildChatFailureUxTest.kt` (11 tests)
- `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` (Send catch, coaching, badge, session chip)
- Reused (no fork): `GrokBuildAuthRecovery`, `LocalLlmSendUx`, `LocalLlmWorkbenchPanel`

**Tests:**

| Suite | Count | Result |
|-------|------:|--------|
| `LocalLlmSendUxTest` | 14 | green |
| `GrokBuildSendUxTest` | 20 | green |
| `GrokBuildChatFailureUxTest` | 11 | green |
| Verifier scoped (client/chat/ui/model) | 268 | green |

## Manager notes

Checkpoint AC vs code:

1. **Labels** — `LocalLlmSendUx.sendPathLabel` returns `Chat` \| `Agent · dry-run` \| `Agent · APPLY`; tool-window badge + workbench status lines use it; tooltips match.
2. **Local LLM failures** — blank URL, offline, unreachable remain distinct pure helpers; agent path still uses `LocalLlmSendUx.formatFailure(..., agentMode=true)`.
3. **Grok Build recovery** — single string catalog in `GrokBuildAuthRecovery`; `GrokBuildSendUx` thin wrappers + proxy-network copy only (no forked login messaging).
4. **Send catch** — chat path uses `GrokBuildChatFailureUx.formatChatFailure` (not bare `Error: ${e.message}` for auth/proxy classes).
5. **Session chip (optional)** — implemented: `Grok · email` / `Session expired` / `No session`; click → `refreshGrokBuildSession` without provider re-select.
6. **No agent claims off Local** — workbench mounted only when `activeProvider == LOCAL_LLM`; non-Local/non-Grok badges hidden; agent labels only on Local branch.
7. **Unit tests** — pure UX suites present and green under verifier.

No remaining functional gaps for section AC. Residual product risk is e2e/manual smoke only (unit tests do not hit live auth/proxy).

## Remaining gaps

- None for section AC.
- Out of scope residual: full Gradle suite / live endpoint smoke (section-05 / manual).

## Next iteration focus (if not done)

- N/A — section complete.

**Manager verdict:** done — `SIGNAL: SECTION_DONE section-04-ux-recovery`

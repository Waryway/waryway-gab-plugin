# Iteration 01 — Grok Build auth & session reliability

**Section:** `.grok/org/improve-grok-build-interaction/sections/section-01-auth-session.md`  
**Iteration:** 01 of 3  
**Status:** done  
**Manager:** section-01-auth-session (proxy child, checkpoint)

## This iteration focus

- [x] Pure recovery helpers for missing vs expired session and auth-class HTTP failures (401/403), with unit tests
- [x] Keep `GrokBuildAuth.readSession()` / `hasUsableSession()` source of truth; no blind parser rewrite
- [x] Settings: live session preferred over PasswordSafe override; refresh/re-read after `grok login` without IDE restart
- [x] Settings UI: live summary + explicit refresh affordance for Grok Build session
- [x] Minimal tool-window / Send path: map GROK_BUILD auth failures to recovery copy (coordinate with section-04 string ownership)

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-s01-01 | `.grok/org/improve-grok-build-interaction/work-orders/wo-s01-01.md` | implementer | done | Pure `GrokBuildAuthRecovery` + settings session APIs + 19 unit tests |
| wo-s01-02 | `.grok/org/improve-grok-build-interaction/work-orders/wo-s01-02.md` | implementer | done | Configurable refresh + tool-window auth coaching / Send error mapping |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Result | **PASS** — 268/268 scoped (`client`/`chat`/`ui`/`model`); see `delegations/verifier-report-iter-01.md` |

## Results

**Outcome:** pass — all section acceptance criteria met on iteration 01  

**Files changed (workers):**

- `src/main/kotlin/com/waryway/gab/client/GrokBuildAuthRecovery.kt` (**new**)
- `src/main/kotlin/com/waryway/gab/settings/WarywayGabSettings.kt`
- `src/main/kotlin/com/waryway/gab/settings/WarywayGabConfigurable.kt`
- `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` (auth/session regions only)
- `src/test/kotlin/com/waryway/gab/client/GrokBuildAuthRecoveryTest.kt` (**new**)
- `src/test/kotlin/com/waryway/gab/client/GrokBuildAuthTest.kt`
- (no change to `GrokBuildAuth.kt` parser — semantics already correct)

**Tests:** Verifier report: 268 passed, 0 failed. Section-local suites: `GrokBuildAuthTest` 7, `GrokBuildAuthRecoveryTest` 12; related UX (`GrokBuildSendUx` / `GrokBuildChatFailureUx`) also green and delegate to recovery string table.

## Manager notes

- Code inspection confirms single string table in `GrokBuildAuthRecovery`; `GrokBuildSendUx` / `GrokBuildChatFailureUx` delegate rather than forking login copy.
- Live-prefer precedence documented on `grokBuildAccessToken()`; Settings UI help + **Refresh session** button rebind summary without IDE restart.
- Send path re-reads credentials via `createClient`/`getApiKey` → `readSession()` (no session memoization).
- Parallel section-02/04 UX helpers land cleanly against this API; residual e2e risk is live endpoint smoke only (noted by verifier).

## Remaining gaps

- None for section acceptance. Optional later polish: full-suite regression is section-05; live Grok Build e2e not covered by unit tests.

## Next iteration focus (if not done)

- N/A — section complete

**Manager verdict:** SECTION_DONE

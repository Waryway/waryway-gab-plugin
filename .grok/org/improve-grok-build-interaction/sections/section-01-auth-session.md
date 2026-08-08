# Section — Grok Build auth & session reliability

**Section ID:** section-01-auth-session  
**Project:** improve-grok-build-interaction  
**Plan:** `.grok/org/improve-grok-build-interaction/plan.md`  
**Status:** done  
**Manager:** manager-section-01-auth-session (checkpoint iteration 01 → SECTION_DONE)

## Goal (from Director)

Make Grok Build **connect/auth** reliable and self-explanatory when the session is missing, expired, or stale after `grok login`. Operators should recover without restarting the IDE: discover `~/.grok/auth.json`, prefer live session over dead override, surface clear coaching, and refresh credentials for the next Send.

## Acceptance criteria

- [x] Missing session and expired session produce distinct, actionable coaching (`grok login`, auth file path) in settings and/or tool window — not a bare stack-style message
- [x] Live `GrokBuildAuth.readSession()` / `hasUsableSession()` remains source of truth; expired tokens are not treated as usable
- [x] After a successful `grok login` (new/updated `auth.json`), the plugin can pick up the session on the next Send or via an explicit refresh without full IDE restart
- [x] Optional PasswordSafe override does not silently win over a **fresh** live session; behavior is documented in code comments or settings help
- [x] HTTP auth failures from cli-chat-proxy (e.g. 401/403) map to recovery copy that mentions re-login when appropriate (helper may live here or be shared with section-04 — avoid duplicate string tables)
- [x] Unit tests for pure auth/session helpers (parse, expiry skew, usable check, any new recovery formatter)
- [x] No rewrite of working `auth.json` parser unless a real parse bug is found and tested

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `GrokBuildAuth.kt` (only if bugs/gaps proven)
- `WarywayGabSettings` Grok Build key/session APIs (`getApiKey`, `hasApiKey`, `grokBuildSessionSummary`, any refresh helper)
- `WarywayGabConfigurable` Grok Build group (live summary / refresh affordance)
- Minimal tool-window hooks for session refresh / coaching **if** required for acceptance (coordinate with section-04)
- Pure recovery helpers for auth-class failures (prefer new small object over bloating Swing code)
- Unit tests under `src/test/.../client` or `settings`

**Out of scope:**

- Cloud `ModelProvider.GROK` (api.x.ai) credential redesign
- Local LLM base URL / health (section-04 / section-03)
- Stream merge algorithm rewrite (section-02)
- Version bump / CHANGELOG (section-05)
- Unrelated PasswordSafe changes for Gab/Grok API keys

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Auth | `src/main/kotlin/com/waryway/gab/client/GrokBuildAuth.kt` |
| Recovery (new) | `src/main/kotlin/com/waryway/gab/client/GrokBuildAuthRecovery.kt` |
| Settings | `src/main/kotlin/com/waryway/gab/settings/WarywayGabSettings.kt` |
| Settings UI | `src/main/kotlin/com/waryway/gab/settings/WarywayGabConfigurable.kt` |
| Client headers | `src/main/kotlin/com/waryway/gab/client/GabClient.kt` (auth header application only if needed) |
| Tool window (minimal) | `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` |
| Tests | `src/test/kotlin/com/waryway/gab/client/GrokBuildAuthTest.kt` (+ new pure helper tests) |
| Docs context | `HOW_TO_LOAD_IN_IDE.md` (auth section; light touch only if copy must match) |

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/improve-grok-build-interaction/iterations/section-01-auth-session/iteration-01.md` | done | pass — all AC met; verifier 268/268 scoped |

## Checkpoint evidence (Manager inspection)

| Criterion | Evidence |
|-----------|----------|
| Distinct missing vs expired coaching | `GrokBuildAuthRecovery.coachingMissingSession` / `coachingExpiredSession`; settings summary + tool-window coaching use `classifySession` |
| Live session source of truth | Unchanged `GrokBuildAuth.readSession` / `hasUsableSession` / 60s skew; `grokBuildAccessToken` prefers usable live over PasswordSafe |
| Refresh without IDE restart | `refreshGrokBuildSession()` + Configurable **Refresh session** button; Send path live-reads via `getApiKey` → `readSession` |
| Override precedence documented | KDoc on `grokBuildAccessToken()`; Configurable help text |
| 401/403 recovery mapping | `formatAuthFailure` / `isAuthClassFailure`; UI via `GrokBuildChatFailureUx` → `GrokBuildSendUx` → recovery helper |
| Unit tests | `GrokBuildAuthTest` (7), `GrokBuildAuthRecoveryTest` (12); verifier package pass |
| No parser rewrite | `GrokBuildAuth.kt` left intact (wo-s01-01) |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `.grok/org/improve-grok-build-interaction/iterations/section-01-auth-session/iteration-01.md`
- `.grok/org/improve-grok-build-interaction/work-orders/wo-s01-01.md`
- `.grok/org/improve-grok-build-interaction/work-orders/wo-s01-02.md`
- `.grok/org/improve-grok-build-interaction/delegations/workers-queue-section-01.json`
- `.grok/org/improve-grok-build-interaction/delegations/workers-queue.json` (merged orders)
- `.grok/org/improve-grok-build-interaction/delegations/verifier-report-iter-01.md`
- `src/main/kotlin/com/waryway/gab/client/GrokBuildAuthRecovery.kt`
- `src/test/kotlin/com/waryway/gab/client/GrokBuildAuthRecoveryTest.kt`

**Director sign-off:** done (2026-07-17 verify)

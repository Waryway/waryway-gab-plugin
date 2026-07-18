# Section — Workbench / send UX & recovery copy

**Section ID:** section-04-ux-recovery  
**Project:** improve-grok-build-interaction  
**Plan:** `.grok/org/improve-grok-build-interaction/plan.md`  
**Status:** done  
**Manager:** manager-section-04-ux-recovery

## Goal (from Director)

Make dry-run vs APPLY and chat vs agent send paths **obvious and correct**, keep Local LLM workbench badges/status truthful, and ensure error messages guide recovery (login, base URL, offline proxy). Prefer pure string/label helpers (`LocalLlmSendUx` pattern) so UX is unit-testable without Swing.

## Acceptance criteria

- [x] Send path labels remain correct: `Chat` | `Agent · dry-run` | `Agent · APPLY` with matching tooltips/status lines
- [x] Local LLM offline / blank base URL / unreachable failures stay distinct and actionable
- [x] Grok Build failures (no session, expired, 401-class, network) use recovery-oriented copy (shared with or built on section-01 helpers — single source of truth for strings)
- [x] Tool window Send catch paths use the pure formatters for GROK_BUILD as well as LOCAL_LLM (no bare `Error: ${e.message}` for auth/proxy classes when a formatter exists)
- [x] Optional: persistent Grok Build session status affordance in main UI (badge/system line/refresh) without requiring full provider re-select
- [x] Workbench does not claim agent mode for non–Local-LLM providers
- [x] Unit tests for all new/changed pure UX helpers (`LocalLlmSendUxTest` and/or `GrokBuild*Ux` tests)

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `LocalLlmSendUx.kt` and tests
- New pure Grok Build UX helper if section-01 did not already add one (coordinate — do not fork string tables)
- `LocalLlmWorkbenchPanel.kt` badges/status
- `WarywayGabToolWindowPanel.kt` send path badge, failure formatting, light session status UI
- Coaching strings consistency with settings (no large first-run redesign)

**Out of scope:**

- Auth.json parser internals (section-01 owns parser bugs)
- SSE merge math (section-02)
- Agent poll loop (section-03)
- Full theme redesign / unrelated panels (ActivityLog, attachments)
- Version/CHANGELOG (section-05)

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Pure UX | `src/main/kotlin/com/waryway/gab/ui/LocalLlmSendUx.kt` |
| Pure UX (new) | `src/main/kotlin/com/waryway/gab/ui/GrokBuildSendUx.kt` |
| Pure UX (facade) | `src/main/kotlin/com/waryway/gab/ui/GrokBuildChatFailureUx.kt` |
| Auth strings (section-01) | `src/main/kotlin/com/waryway/gab/client/GrokBuildAuthRecovery.kt` |
| Workbench | `src/main/kotlin/com/waryway/gab/ui/LocalLlmWorkbenchPanel.kt` |
| Tool window | `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` |
| Auth summary (read/wire) | `src/main/kotlin/com/waryway/gab/settings/WarywayGabSettings.kt` (`grokBuildSessionSummary`) |
| Tests | `src/test/kotlin/com/waryway/gab/ui/LocalLlmSendUxTest.kt`, `GrokBuildSendUxTest.kt`, `GrokBuildChatFailureUxTest.kt` |

## Coordination note

If section-01 adds a pure auth recovery formatter, **reuse it** here for tool-window Send/error wiring. Do not create a second parallel message catalog.

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/improve-grok-build-interaction/iterations/section-04-ux-recovery/iteration-01.md` | done | wo-04-01 + wo-04-02 done; verifier 268 green; all AC met |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `.grok/org/improve-grok-build-interaction/work-orders/wo-04-01.md`
- `.grok/org/improve-grok-build-interaction/work-orders/wo-04-02.md`
- `.grok/org/improve-grok-build-interaction/iterations/section-04-ux-recovery/iteration-01.md`
- `.grok/org/improve-grok-build-interaction/delegations/verifier-report-iter-01.md`
- `src/main/kotlin/com/waryway/gab/ui/GrokBuildSendUx.kt`
- `src/main/kotlin/com/waryway/gab/ui/GrokBuildChatFailureUx.kt`
- `src/test/kotlin/com/waryway/gab/ui/GrokBuildSendUxTest.kt`
- `src/test/kotlin/com/waryway/gab/ui/GrokBuildChatFailureUxTest.kt`

**Director sign-off:** done (2026-07-17 verify)

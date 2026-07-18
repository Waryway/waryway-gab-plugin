# Section — First-class Grok (xAI) provider path

**Section ID:** section-01-grok-provider  
**Project:** grok-support-agent-stability  
**Plan:** `.grok/org/grok-support-agent-stability/plan.md`  
**Status:** done  
**Manager:** manager-section-01-grok-provider (child / proxy)

## Goal (from Director)

Make **Grok (xAI)** a first-class, reliable cloud provider path in the plugin: correct base URL/auth, model catalog and defaults, settings/UX, and GabClient request/response behavior aligned with xAI’s OpenAI-compatible API so Grok is a trustworthy default alongside Gab AI.

## Acceptance criteria

- [x] `ModelProvider.GROK` base URL, key help, and display name remain correct; active provider + key selection cannot silently use Gab credentials for Grok models (or vice versa)
- [x] `ModelCatalog` Grok default/fallbacks/preferred order match current product intent (document any deliberate model id updates in work-order notes)
- [x] `GabClient` chat/completions + models listing work for Grok without Gab-only assumptions (credits skipped; tools/stream enabled where supported)
- [x] Settings UI “Grok (xAI)” group: API key, default model, Test Connection remain accurate; coaching/copy mentions Grok correctly where relevant
- [x] Scoped unit tests or assertions for catalog + request construction differences where code changes land
- [x] No unrelated refactors; Local LLM path unchanged unless a shared bug is proven

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- `ModelProvider`, `ModelCatalog`, Grok-related settings state
- `WarywayGabConfigurable` Grok group / coaching strings that mislead about Grok
- `GabClient` provider-aware request quirks needed for xAI (e.g. omit Gab-only fields, stream/tools defaults)
- `WarywayGabSettings.createClient` / active provider routing
- Existing `ModelCatalogTest` extensions

**Out of scope:**

- Local LLM `/api/agent` server protocol
- Full UI redesign or new settings pages
- Agent loop exit logic (section-03) and deep SSE parser fixes (section-02) — only touch if required for Grok request compatibility
- Marketing/docs rewrite beyond code comments / test names as needed

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Provider | `src/main/kotlin/com/waryway/gab/model/ModelProvider.kt` |
| Catalog | `src/main/kotlin/com/waryway/gab/model/ModelCatalog.kt` |
| Settings | `src/main/kotlin/com/waryway/gab/settings/WarywayGabSettings.kt` |
| Configurable | `src/main/kotlin/com/waryway/gab/settings/WarywayGabConfigurable.kt` |
| Client | `src/main/kotlin/com/waryway/gab/client/GabClient.kt` |
| UI routing | `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` |
| Tests | `src/test/kotlin/com/waryway/gab/model/ModelCatalogTest.kt`, `src/test/kotlin/com/waryway/gab/client/GabClientJsonTest.kt` |

## Investigation prompts for Manager

1. Confirm `settings.createClient(ModelProvider.GROK)` always hits `api.x.ai` with the Grok key.
2. Compare request body for Grok vs Gab (`buildJsonChatRequest`) — any field Gab tolerates that xAI rejects?
3. Verify model list filtering (`isGrok` / `belongsToProvider`) does not hide valid xAI models or include Gab models.
4. Test Connection path for Grok: models list or lightweight probe?

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `.grok/org/grok-support-agent-stability/iterations/section-01-grok-provider/iteration-01.md` | done | acceptance met; verifier pass (scoped ModelCatalogTest + GabClientJsonTest) |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done` (`SIGNAL: SECTION_DONE` / managers-queue status=done)
- [x] Artifacts listed below exist

**Artifacts:**

- `.grok/org/grok-support-agent-stability/iterations/section-01-grok-provider/iteration-01.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-01-01.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-01-02.md`
- `.grok/org/grok-support-agent-stability/work-orders/wo-01-03.md`
- Production: `ModelProvider.kt`, `ModelCatalog.kt`, `WarywayGabSettings.createClient` key+URL pairing, `GabClient.buildJsonChatRequest` Grok-safe body
- Tests: `ModelCatalogTest`, `GabClientJsonTest` Grok cases

**Director sign-off:** done (Director MODE=verify 2026-07-17)

# Iteration 01 — First-class Grok (xAI) provider path

**Section:** `.grok/org/grok-support-agent-stability/sections/section-01-grok-provider.md`  
**Iteration:** 01 of 03  
**Status:** done

## This iteration focus

- [x] Harden Grok provider identity: base URL (`https://api.x.ai/v1`), key isolation, display name; `createClient(GROK)` never uses Gab credentials or Gab base URL
- [x] Align `ModelCatalog` Grok default/fallbacks/preferred order with product intent; ensure `isGrok` / `belongsToProvider` filter correctly
- [x] Ensure `GabClient` chat/completions + models path has no Gab-only assumptions that break xAI (credits skip, tools/stream OK, no localllm payload on cloud)
- [x] Settings UI “Grok (xAI)” group + onboarding coaching remain accurate; Test Connection uses Grok key + models list filter
- [x] Extend unit tests for catalog + Grok request construction differences

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-01-01 | `.grok/org/grok-support-agent-stability/work-orders/wo-01-01.md` | implementer | done | Provider/catalog/settings routing hardened; ModelCatalogTest extended |
| wo-01-02 | `.grok/org/grok-support-agent-stability/work-orders/wo-01-02.md` | implementer | done | GabClient Grok-safe request shape; GabClientJsonTest Grok cases |
| wo-01-03 | `.grok/org/grok-support-agent-stability/work-orders/wo-01-03.md` | implementer | done | Settings/UX + coaching Grok accuracy polish |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Command | `./gradlew.bat test --tests "com.waryway.gab.model.ModelCatalogTest" --tests "com.waryway.gab.client.GabClientJsonTest"` |
| Result | **pass** (Manager checkpoint ran command → BUILD SUCCESSFUL) |
| Full suite note | 176 tests; 2 pre-existing OOS failures (`InputNormalizerTest`, `GolandMcpExecutorTest`); model/client/chat scoped green |

## Results

**Outcome:** acceptance met — first-class Grok path hardened across provider identity, catalog, client request shape, and settings/UX  

**Files changed (union of workers):**

- `src/main/kotlin/com/waryway/gab/model/ModelProvider.kt`
- `src/main/kotlin/com/waryway/gab/model/ModelCatalog.kt`
- `src/main/kotlin/com/waryway/gab/settings/WarywayGabSettings.kt`
- `src/main/kotlin/com/waryway/gab/settings/WarywayGabConfigurable.kt`
- `src/main/kotlin/com/waryway/gab/client/GabClient.kt`
- `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt`
- `src/main/resources/messages/WarywayGabBundle.properties`
- `src/test/kotlin/com/waryway/gab/model/ModelCatalogTest.kt`
- `src/test/kotlin/com/waryway/gab/client/GabClientJsonTest.kt`

**Tests:**

- Scoped verifier: `ModelCatalogTest` + `GabClientJsonTest` → BUILD SUCCESSFUL
- Workers reported full suite: 2 OOS failures unrelated to this section

## Manager notes

- Catalog default remains **`grok-4.3`**; fallbacks `4.3 → 4.2 → 4 → 3 → build-0.1` — no deliberate model-id product change.
- Credential isolation is structural: PasswordSafe attributes keyed by `provider.name`; `createClient(p)` always pairs `getApiKey(p)` + `getBaseUrl(p)`.
- `isGrok(ModelInfo)` accepts `ownedBy=xai` without “grok” in id; local `.gguf` / `ownedBy=localllm` never enter cloud Grok list.
- `buildJsonChatRequest` gates Local-only fields (`localllm`, forced `max_tokens`) to `LOCAL_LLM` only; Grok gets OpenAI-compatible body with tools/stream.
- Settings Test Connection + coaching bind GROK → `console.x.ai`, separate keys from Gab.
- Local LLM path not redesigned; only shared catalog/filter contracts exercised.

## Remaining gaps

- None in section scope. Pre-existing full-suite failures (`InputNormalizerTest`, `GolandMcpExecutorTest`) are out of scope for section-01.
- Live xAI network smoke (real key) not run in CI unit tests — expected; Test Connection path is code-complete.

## Next iteration focus (if not done)

- N/A — section acceptance met on iteration 01.

**Manager verdict:** SECTION_DONE

# Iteration 01 — Chat streaming residual polish

**Section:** `.grok/org/improve-grok-build-interaction/sections/section-02-chat-stream.md`  
**Iteration:** 01 of 3  
**Status:** done  
**Manager:** section-02-chat-stream (proxy / child)  
**Playbook:** general

## This iteration focus

- [x] Lock snapshot-safe merge + UI coalesce + SSE accumulator contracts (green scoped tests; no redesign without a failing case)
- [x] Confirm GROK_BUILD chat completions keep cli-chat-proxy headers and non-2xx / SSE error fail the turn (not empty “success”)
- [x] Confirm Stop continues to abort active SSE body and unblock the worker
- [x] Add pure helper + unit tests for actionable GROK_BUILD chat failure messages (401/403 session, connect, HTTP body); wire chat error path away from bare `Error: ${e.message}` for GROK_BUILD
- [x] Leave auth.json parsing, `/api/agent`, workbench badges, packaging to other sections

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-02-01 | `.grok/org/improve-grok-build-interaction/work-orders/wo-02-01.md` | implementer | done | Stream contract lock: merger/SSE/abort/headers + tests |
| wo-02-02 | `.grok/org/improve-grok-build-interaction/work-orders/wo-02-02.md` | implementer | done | Actionable GROK_BUILD chat failure UX helper + wire |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Command | scoped `client` / `chat` / `ui` / `model` (see verifier-report-iter-01.md) |
| Result | **PASS** — 268/268 green (0 fail / 0 error / 0 skip) |

## Results

**Outcome:** SECTION_DONE — all section acceptance criteria met on iteration 01.

**Files changed (workers):**

| Order | Files |
|-------|--------|
| wo-02-01 | `GrokBuildAuth.kt` (`requestHeaders`), `GabClient.kt` (`applyProviderAuth` via pure map), `GrokBuildAuthTest.kt` (constants), `GabClientJsonTest.kt` (GROK_BUILD OpenAI body + `GabApiException` body retention) |
| wo-02-02 | `GrokBuildChatFailureUx.kt` (new facade), `GrokBuildSendUx.kt` (`shortBodyDetail`), `WarywayGabToolWindowPanel.kt` (chat catch wire), `GrokBuildChatFailureUxTest.kt`, `GrokBuildSendUxTest.kt` |

**Tests (verifier, packages client+chat+ui+model):**

| Metric | Count |
|--------|------:|
| Run | 268 |
| Passed | 268 |
| Failed | 0 |

Section-relevant suites all green: `StreamContentMergerTest` (10), `StreamUiCoalescerTest` (9), `GabSseAccumulatorTest` (29), `GabClientJsonTest` (16), `GrokBuildAuthTest` (7), `GrokBuildChatFailureUxTest` (11), `GrokBuildSendUxTest` (20), `LocalLlmSendUxTest` (14).

## Manager notes

**Checkpoint review (AC vs landed code):**

1. **Snapshot-safe merge / UI coalesce / no quadratic** — Existing contracts retained; no merger/coalescer redesign. Tests lock snapshot vs delta, quadratic guard, safety cap, UI coalesce. Code inspection of stream path intact.
2. **GROK_BUILD headers** — `GrokBuildAuth.requestHeaders(accessToken, modelForOverride)` is single source of truth; `GabClient.applyProviderAuth` applies the map for GROK_BUILD (Authorization, `X-XAI-Token-Auth`, client version/surface, User-Agent, optional `x-grok-model-override`). OpenAI-compatible chat body covered in `GabClientJsonTest`.
3. **Fail-not-empty-success** — Non-2xx → `GabApiException("Chat failed: HTTP …", body)`; SSE `Error` → `GabApiException("SSE stream error: …")`. Body retention asserted.
4. **Stop/abort** — `abortActiveStream()` `getAndSet(null)` + `close()`; IOException after clear maps to `cancelled=true`; no invented `finishReason="stop"`.
5. **Failure UX** — Chat catch uses `GrokBuildChatFailureUx.formatChatFailure` (GROK_BUILD → SendUx auth/network/body; LOCAL_LLM → LocalLlmSendUx unchanged; other cloud generic + body). Unit tests cover 401/403 login coaching, unreachable, HTTP 500 body snippet, SSE preserve, null fallback, LOCAL non-regression.
6. **No redesign without evidence** — Confirmed; residual stream quirks did not require merger changes.

**Worker claim note:** wo-02-01 report mentioned pure tests for header map with/without model override; landed `GrokBuildAuthTest` locks constants (7 tests) but not a full `requestHeaders` map assertion. Implementation is correct and applied live; residual risk only (see below) — not AC-blocking given verifier green and header helper as sole apply path.

## Remaining gaps

- None blocking section AC.
- Residual (OOS / polish): pure unit assert of full `requestHeaders` name→value map (+ blank model omits override); live HTTP/mock-server header apply and abort-unblocks-readLine not covered by unit suite; full-suite regression deferred to section-05 ship; e2e against real cli-chat-proxy not in scope.

## Next iteration focus (if not done)

- N/A — section complete on iteration 01.

**Manager verdict:** SECTION_DONE

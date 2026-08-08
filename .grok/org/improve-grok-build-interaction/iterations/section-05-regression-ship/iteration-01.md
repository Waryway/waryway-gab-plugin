# Iteration 01 — Cross-cutting regression tests + ship readiness

**Section:** `.grok/org/improve-grok-build-interaction/sections/section-05-regression-ship.md`  
**Iteration:** 01 of 3  
**Status:** done  
**Manager:** section-05-regression-ship (proxy / child)  
**Playbook:** general

## This iteration focus

- [x] Audit pure unit coverage for sections 01–04 landed surfaces; fill only real thin gaps (no brittle full-IDE UI tests)
- [x] Bump `pluginVersion` 0.1.29 → 0.1.30 (product code shipped in 01–04) + CHANGELOG note for Grok Build interaction
- [x] Refresh README / `HOW_TO_LOAD_IN_IDE.md` version stamps stuck on 0.1.27
- [x] Confirm full or scoped Gradle test suite green; document filter rationale if full suite has pre-existing OOS failures
- [x] No unrelated refactors or dependency upgrades

## Work orders

| Order ID | Path | Worker | Status | Summary |
|----------|------|--------|--------|---------|
| wo-05-01 | `.grok/org/improve-grok-build-interaction/work-orders/wo-05-01.md` | implementer | WORK_DONE | Audit + 3 pure `requestHeaders` cases; 271 scoped green |
| wo-05-02 | `.grok/org/improve-grok-build-interaction/work-orders/wo-05-02.md` | implementer | WORK_DONE | 0.1.30 + CHANGELOG + README/HOW_TO_LOAD stamps |

## Verifier (optional)

| Field | Value |
|-------|-------|
| Required | yes |
| Type | `build-test` |
| Command (preferred) | `./gradlew.bat test` |
| Command (fallback) | `./gradlew.bat test --tests "com.waryway.gab.client.*" --tests "com.waryway.gab.chat.*" --tests "com.waryway.gab.ui.*" --tests "com.waryway.gab.model.*"` plus document any extra packages if wo-05-01 adds tests outside these |
| Baseline (pre-section) | 268 scoped green (client/chat/ui/model) — see `delegations/verifier-report-iter-01.md` |
| Report | `delegations/verifier-report-section-05.md` |
| Result | **PASS** full suite 292/292; `pluginVersion=0.1.30` |

## Results

**Outcome:** PASS — all section acceptance criteria met in one iteration.

**Files changed (workers):**

| Worker | Files |
|--------|-------|
| wo-05-01 | `src/test/kotlin/com/waryway/gab/client/GrokBuildAuthTest.kt` (+3 pure `requestHeaders` cases) |
| wo-05-02 | `gradle.properties` (`pluginVersion` 0.1.29 → 0.1.30); `CHANGELOG.md` (`## [0.1.30]`); `README.md`; `HOW_TO_LOAD_IN_IDE.md` |

**Tests:**

| Stage | Command | Result |
|-------|---------|--------|
| Worker wo-05-01 | scoped client/chat/ui/model | 271 passed (baseline 268 + 3) |
| Verifier | `./gradlew.bat test` full suite | **292 passed, 0 failed, 0 errors, 0 skipped** |

**Coverage audit (wo-05-01):** All AC surfaces already locked except residual `GrokBuildAuth.requestHeaders` map contract — filled with three pure asserts (auth headers + model override include/omit/trim). Stream merge, auth recovery, Local LLM send UX, agent terminal/cancel, Grok Build send/chat failure UX — all Y.

**Ship (wo-05-02):** Install-from-Disk of **0.1.30** distinguishable from stream-only 0.1.27; known-bad 0.1.23 warning retained.

## Manager notes

Checkpoint (2026-07-17): both workers WORK_DONE; verifier `VERIFIER_DONE` with full suite green. No rework wave needed.

| Area | Landed | Pure tests (post-iter) |
|------|--------|------------------------|
| Auth / session | recovery + requestHeaders map | `GrokBuildAuthTest` 10, `GrokBuildAuthRecoveryTest` 12 |
| Stream / chat failure | stream contract + failure UX | merger 10, coalescer 9, SSE 29, chat-fail 11 |
| Agent terminal | pure finish + cancel/timeout | `LocalLlmAgentSessionTest` 35, `AgentClientTest` 12 |
| Send UX | Grok Build + Local LLM | send UX 20, local 14 |

**Ship decision executed:** product main sources from 01–04 → bump **required** and completed (0.1.30).

## Remaining gaps

- None for section AC. (Marketplace publish / historical zip rebuild remain OOS by design.)

## Next iteration focus (if not done)

- N/A — section complete.

**Manager verdict:** SECTION_DONE

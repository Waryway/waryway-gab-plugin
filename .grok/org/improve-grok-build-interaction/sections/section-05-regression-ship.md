# Section — Cross-cutting regression tests + ship readiness

**Section ID:** section-05-regression-ship  
**Project:** improve-grok-build-interaction  
**Plan:** `.grok/org/improve-grok-build-interaction/plan.md`  
**Status:** done  
**Manager:** section-05-regression-ship (proxy / child)

## Goal (from Director)

Close **cross-cutting** test gaps for Grok Build interaction changes from sections 01–04, keep the scoped suite green, and if product code shipped, bump version + short CHANGELOG / load notes so Install-from-Disk is distinguishable from 0.1.29 and earlier stream-only builds.

## Acceptance criteria

- [x] Regression coverage exists for: stream merge (guard), Grok Build auth usable/expired, Local LLM send UX, agent terminal/cancel helpers introduced by prior sections
- [x] New tests are pure/unit-style (no brittle full-IDE UI tests required)
- [x] `./gradlew.bat test` green, or a documented scoped filter with rationale if OOS failures pre-exist
- [x] If any main sources changed across the project: `pluginVersion` bumped past 0.1.29; `CHANGELOG.md` Unreleased/new version note; README / `HOW_TO_LOAD_IN_IDE.md` version hints not stuck on 0.1.27
- [x] If **no** source changes (docs/tests only): document that ship bump is optional and leave version unchanged only with explicit note in section ticket
- [x] No unrelated refactors or dependency upgrades

**AC notes (checkpoint):** Product main sources shipped in 01–04 → version bump path taken (not optional). Full suite 292/292 green (no OOS filter needed). Only pure test delta: +3 `GrokBuildAuth.requestHeaders` cases. Ship docs/version: 0.1.30.

## Manager playbook

| Field | Value |
|-------|-------|
| Type | `general` |
| Max iterations | 3 |
| Verifier required | yes |

## Scope boundaries

**In scope:**

- Filling missing unit tests that sections 01–04 left thin (integration of behaviors)
- `gradle.properties` `pluginVersion`
- `CHANGELOG.md`, light `README.md` / `HOW_TO_LOAD_IN_IDE.md` version stamps
- Running test suite and recording results in iteration notes

**Out of scope:**

- New product features beyond test/docs/version (push product fixes back to owning section)
- Marketplace publish / CI pipeline redesign
- Rebuilding unrelated historical zip artifacts

## Soft dependency

Prefer starting after sections 01–04 have landed their code so this section packages the real delta. If concert spawns early, focus on baseline regression guards first, then re-run ship steps after code sections complete.

**Prepare note (2026-07-17):** Sections 01–04 are SECTION_DONE. Product main sources shipped → **version bump required** (0.1.29 → 0.1.30). Baseline: 268 scoped tests green.

**Checkpoint note (2026-07-17):** Iteration 01 complete. wo-05-01 + wo-05-02 WORK_DONE; verifier full suite 292 passed; `pluginVersion=0.1.30`. All AC met → **SECTION_DONE**.

## Key files (Director seeds — Manager opens only what workers touch)

| Area | Path |
|------|------|
| Version | `gradle.properties` |
| Changelog | `CHANGELOG.md` |
| Load guide | `HOW_TO_LOAD_IN_IDE.md` |
| README | `README.md` (version line only if stale) |
| Tests | `src/test/kotlin/com/waryway/gab/**` (gaps only) |
| Bump helper | `scripts/bump-version.ps1` (optional) |

## Iteration index

| Iteration | Path | Status | Score / outcome |
|-----------|------|--------|-----------------|
| 01 | `iterations/section-05-regression-ship/iteration-01.md` | done | PASS — +3 requestHeaders tests; 0.1.30 ship; full suite 292/292 |

## Director verification

- [x] All acceptance criteria checked
- [x] Manager returned `done`
- [x] Artifacts listed below exist

**Artifacts:**

- `iterations/section-05-regression-ship/iteration-01.md`
- `work-orders/wo-05-01.md` (WORK_DONE — GrokBuildAuthTest requestHeaders)
- `work-orders/wo-05-02.md` (WORK_DONE — 0.1.30 + CHANGELOG/README/HOW_TO_LOAD)
- `delegations/verifier-report-section-05.md` (292 passed, pluginVersion=0.1.30)
- `src/test/kotlin/com/waryway/gab/client/GrokBuildAuthTest.kt` (+3 pure cases)
- `gradle.properties` (`pluginVersion = 0.1.30`)
- `CHANGELOG.md` (`## [0.1.30]`)
- `README.md`, `HOW_TO_LOAD_IN_IDE.md` (version stamps → 0.1.30)

**Director sign-off:** done (2026-07-17 verify)

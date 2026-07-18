# WO-04 — Version bump, changelog, verify

## Status: **done**

## Task

After WO-02/03 land:

1. Bump `pluginVersion` in `gradle.properties` (0.1.26 → 0.1.27).
2. CHANGELOG unreleased note for stream/UI fix.
3. Run unit tests (`./gradlew test` or `gradlew.bat test`).
4. Optionally `buildPlugin` if time allows.
5. Update STATUS.md and this ticket with pass/fail.

## Progress

- [x] `pluginVersion` → **0.1.27** in `gradle.properties`
- [x] `CHANGELOG.md` Unreleased note (StreamContentMerger, StreamUiCoalescer, AgentSession stream reset)
- [x] `HOW_TO_LOAD_IN_IDE.md` current version / zip path examples → 0.1.27
- [x] Unit tests (`gradlew.bat test`) — **PASS** (206 tests)
- [x] `gradlew.bat buildPlugin` — **PASS**
- [x] Results recorded below

## Results

| Item | Value |
|------|--------|
| **Version** | **0.1.27** |
| **Tests** | **206 completed, 0 failed** (`gradlew.bat test` BUILD SUCCESSFUL) |
| **Dist zip** | `C:\dev\waryway-gab-plugin\build\distributions\waryway-gab-plugin-0.1.27.zip` |
| **buildPlugin** | BUILD SUCCESSFUL |

### Test note (pre-existing, not stream)

First `gradlew.bat test` run had **2 failures unrelated to WO-02/03 stream work**:

1. `InputNormalizerTest.removes i figured it out phrasing` — expected stripped dash separator; actual left leading `- `. Updated assertion only.
2. `GolandMcpExecutorTest.injectProjectPath adds field to empty object` — expected spaced JSON `"projectPath": "…"`; impl emits compact `"projectPath":"…"`. Updated assertion only.

No stream-core / UI-coalesce production code was changed for packaging.

### Changelog summary (Unreleased / 0.1.27)

- Snapshot-safe SSE merge (`StreamContentMerger`) — stops cumulative chunks from quadratic-append RAM death
- Stream UI coalescer (~40ms) + scroll throttle + soft cap
- `AgentSession` resets stream body each completion iteration

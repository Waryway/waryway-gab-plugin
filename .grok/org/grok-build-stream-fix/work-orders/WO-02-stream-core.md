# WO-02 — SSE snapshot-safe accumulation

## Status: done

## Task

Harden SSE content accumulation so cumulative/snapshot chunks cannot turn into million-character append storms.

## Requirements

1. Detect when a new `delta.content` is a **prefix-extending snapshot** of accumulated content (or full replace of same stream) vs a true append delta.
2. Expose a small pure helper (e.g. `StreamContentMerger`) usable from accumulator and tests.
3. Unit tests:
   - Normal deltas: `"Hel"` + `"lo"` → `"Hello"`
   - Snapshots: `"H"`, `"He"`, `"Hel"`, `"Hello"` → `"Hello"` (not quadratic)
   - Empty / null deltas ignored
   - Tool-call streams still work (no regression)
4. Update this MD with files changed + test results.

## Out of scope

UI coalescing (WO-03).

## Implementation

### Safety choice

Hard cap at `MAX_MERGED_CHARS = 1_000_000` with clear marker
`[stream truncated: content exceeded safety limit]`.
Prevents OOM without complex spam heuristics; true long outputs preserved up to the limit.
Once truncated, further merges are no-ops (marker already present).

### Merge rules (`StreamContentMerger.merge`)

1. empty incoming → keep existing
2. empty existing → take incoming
3. incoming starts with existing → snapshot extension: **replace** with incoming
4. existing starts with shorter incoming → stale snapshot: **keep** existing
5. otherwise → true delta: **append** incoming

### UI delta (`visibleDelta` / `processSseLine`)

- Snapshot replace: emit only new suffix (`incoming.removePrefix(existing)`), or empty if no new chars
- True append: emit `incoming` as-is
- Stale shorter: emit empty (no `SseEvent.Delta`)

## Files changed

| File | Change |
|------|--------|
| `src/main/kotlin/com/waryway/gab/client/StreamContentMerger.kt` | **new** pure helper (`merge`, `mergeUncapped`, `visibleDelta`, safety cap) |
| `src/main/kotlin/com/waryway/gab/client/GabSseAccumulator.kt` | `acceptChunk` uses merger; returns visible fragment; `processSseLine` emits only new UI text |
| `src/test/kotlin/com/waryway/gab/client/StreamContentMergerTest.kt` | **new** unit tests for merge rules + cap |
| `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt` | snapshot / true-delta / empty / tool-call regression tests |

No version bump (WO-04). No UI rewrite (WO-03).

## Test results

```
.\gradlew.bat test --tests "com.waryway.gab.client.*"
BUILD SUCCESSFUL in 21s
```

Client package tests all passed (including new merger + accumulator snapshot cases; prior tool-call / finish_reason / error regressions green).

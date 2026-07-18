# WO-03 — Coalesce stream UI updates + stop EDT thrash

## Task

Stop per-token Swing storms that reprint the same bubble and burn RAM.

## Requirements

1. Coalesce `onStreamDelta` → UI (time batch ~32–50ms and/or min chars); single EDT runnable holds latest pending text, not one task per token.
2. `ChatMessageListPanel`: avoid full revalidate/scroll on every tiny update; throttle scroll; safer preferred-size behavior if needed.
3. Agent turn: clear / reset live streaming body when a new completion iteration starts after tools (or on explicit stream start each iteration).
4. Optional safety cap on live streaming body length with user-visible truncation note.
5. Tests for pure coalescer helper if extracted; keep UI changes minimal.
6. Update this MD with results.

## Coordination

Depends on WO-02 merger semantics if UI also applies snapshot logic; prefer merger in core, UI just displays.

**WO-02 status:** `StreamContentMerger` lives under `client/` and is applied in `GabSseAccumulator` — UI does plain append of already-merged visible deltas. No UI-side snapshot merge needed.

---

## Results

### Status: **done**

### Files changed

| File | Change |
|------|--------|
| `src/main/kotlin/com/waryway/gab/ui/StreamUiCoalescer.kt` | **New** pure thread-safe batcher: `offer` / `drain` / `clear`; force-flush at 4096 chars; default interval constant 40ms. |
| `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` | Wire coalescer + single repeating `javax.swing.Timer` (~40ms). Deltas no longer `invokeLater` per token. Flush on status, complete, error, stop, finally. |
| `src/main/kotlin/com/waryway/gab/ui/ChatMessageListPanel.kt` | Throttle revalidate/scroll to 80ms during stream; always force layout on start/status/complete. Soft cap 200_000 chars + `… (stream truncated for UI)`. |
| `src/main/kotlin/com/waryway/gab/chat/AgentSession.kt` | `onStreamStart()` every iteration (not only iteration 1) so live body resets after tools / retries. |
| `src/test/kotlin/com/waryway/gab/ui/StreamUiCoalescerTest.kt` | **New** 9 pure unit tests. |

### Design notes

- **Coalesce path:** bg `onStreamDelta` → `streamUiCoalescer.offer(delta)` only. EDT timer drains every 40ms into `messageList.appendStreamingDelta`. Size force (≥4096) also schedules one EDT flush.
- **Stop:** `onStop` → `stopStreamFlushTimerOnEdt(flush=true)` so partial text is visible, then “Stopping agent…”.
- **Iteration reset:** `onStreamStart` clears coalescer + `beginStreamingBody()` each completion. Intermediate stream text is replaced after tool rounds; status lines stay in the bubble.
- **WO-02:** UI does not call `StreamContentMerger` (internal to client). Plain append is correct after SSE merge.

### Tests

```text
gradlew.bat test --tests "com.waryway.gab.ui.*" --tests "com.waryway.gab.chat.*"
```

**BUILD SUCCESSFUL** — includes `StreamUiCoalescerTest` (9 tests, 0 failures) + existing chat/ui suites.

### Manual verify

1. Load plugin in IDE sandbox / GoLand with Grok Build configured.
2. Send a long streaming prompt (e.g. “write a long essay about …”).
3. **Expect:** text grows smoothly (~25 UI updates/s), not frozen first token; Task Manager heap should not climb without bound during a normal reply.
4. Multi-tool agent turn: live body should clear when “Continuing agent loop…” starts a new completion; final bubble shows last stream + tool footer.
5. Hit **Stop** mid-stream: partial coalesced text remains, then “Stopping agent…”, buttons re-enable.
6. Very large stream (if forced): at ~200k live chars, body stops growing and shows `… (stream truncated for UI)`; final `completeAgentTurn` still prefers streamed body when non-empty.

# WO-01 — Diagnose Grok Build stream / UI loop

**Status:** completed  
**Owner:** diagnose agent  
**Date:** 2026-07-17  

## Task

Read-only diagnosis of why Grok Build streaming in Waryway Gab can print the first text fragment repeatedly and explode RAM.

## Scope reviewed

| File | Role |
|------|------|
| `src/main/kotlin/com/waryway/gab/client/GabClient.kt` | `chatCompletionStreaming` — line SSE + `onStreamDelta` |
| `src/main/kotlin/com/waryway/gab/client/GabSseAccumulator.kt` | Parse chunks; accumulate content; emit `SseEvent.Delta` |
| `src/main/kotlin/com/waryway/gab/chat/AgentSession.kt` | Agent loop; stream callbacks |
| `src/main/kotlin/com/waryway/gab/ui/WarywayGabToolWindowPanel.kt` | Send path; `invokeLater` per delta |
| `src/main/kotlin/com/waryway/gab/ui/ChatMessageListPanel.kt` | Live bubble append + full layout per delta |
| `src/test/kotlin/com/waryway/gab/client/GabSseAccumulatorTest.kt` | SSE unit tests (no snapshot-vs-delta cases) |
| Directive / plan under `.grok/org/grok-build-stream-fix/` | Hypotheses + WO split |

---

## Confirmed failure modes (ranked)

### 1 — CRITICAL: Naive append of every `delta.content` (snapshot-as-delta)

**Where**

- `GabSseAccumulator.acceptChunk` → `content.append(it)`  
  (`GabSseAccumulator.kt` ~L25–26)
- `GabSseAccumulator.processSseLine` → `SseEvent.Delta(it)` via `deltaFromChunk`  
  (`GabSseAccumulator.kt` ~L114–117, L121, L138–149)
- Live UI: `ChatMessageListPanel.appendStreamingDelta` → `streamingBody.append(delta)`  
  (`ChatMessageListPanel.kt` ~L126–130)

**Behavior**

`extractDeltaContent` finds the first `"content"` string after `"delta"` and returns it with **no classification**. Both the final accumulator and the live UI **always append**.

If cli-chat-proxy / Grok Build (or any OpenAI-compatible peer) re-sends **full content so far** in each chunk (`"H"`, `"He"`, `"Hel"`, `"Hello"`), accumulation becomes:

```text
H + He + Hel + Hello = HHeHelHello
```

That matches the user report: **the first fragment appears again and again** at the start of every “frame,” while the buffer grows **quadratically** (and can explode into multi‑MB strings under long replies). Final `ChatCompletionResult.content` is corrupted the same way as the UI.

**Evidence strength:** High for the code path (unconditional append). Snapshot behavior of the proxy is the most plausible wire-level cause of “first string × huge N + RAM”; not yet proven with a captured SSE log in-repo.

**Tests gap:** `GabSseAccumulatorTest` only covers true incremental deltas / tools / errors — **no** cumulative-snapshot fixtures.

---

### 2 — CRITICAL: Per-token EDT flood + full bubble rebuild

**Where**

- `WarywayGabToolWindowPanel.onSend` agent path (`~L852–860`):

```text
onStreamDelta → SwingUtilities.invokeLater { messageList.appendStreamingDelta(delta) }
```

- `ChatMessageListPanel.appendStreamingDelta` → `refreshActiveTurnText` → `area.text = full rebuild` → `refreshAfterTextChange`  
  (`~L126–143`, `~L175–181`)
- `refreshAfterTextChange`: `area.revalidate/repaint` + `messagesPanel.revalidate/repaint` + `invokeLater { scrollToBottom() }` **every delta**
- `AutoSizeMessageArea.getPreferredSize` (`~L256–262`): `setSize(width, Int.MAX_VALUE)` then measure height — **expensive layout on every revalidate**, cost scales with text length

**Behavior**

One SSE token ⇒ one (or more) EDT runnables ⇒ full `String` rebuild of status + body ⇒ multi-component layout + scroll. Under high token rate this:

- Queues a huge backlog of `invokeLater` work (RAM + “frozen but thrashing” UI)
- Re-paints the same bubble continuously (“re-rendered a huge number of times”)
- Amplifies FM‑1: each bad append is also a full layout pass on a larger string

**Evidence strength:** Confirmed in code; independent of provider. Explains RAM + thrash even with correct deltas; catastrophic with snapshots.

---

### 3 — HIGH: Stream body not cleared on later agent iterations

**Where**

- `AgentSession.run` (`~L73–76`): `onStreamStart()` only when `iterations == 1`
- After tools (`AgentLoopAction.CONTINUE`), next `chatCompletion` still pushes deltas into the same UI turn
- `ChatMessageListPanel.beginStreamingBody` clears `streamingBody`, but is only wired from first `onStreamStart`
- `completeAgentTurn` (`~L96–99`) prefers `streamingBody` over final `response` when non-empty

**Behavior**

Multi-round tool loops can **append stream N+1 onto stream N** in the live bubble. Tool status lines (`appendToAgentTurn`) interleave in the same buffer. Final displayed text may not match `result.finalContent` if streamingBody still holds concatenated partials.

**Evidence strength:** Confirmed control-flow bug for multi-iteration. User’s “did tools finish?” uncertainty may partly be UI corruption vs actual agent progress (agent runs on a background `Thread` + `runBlocking`; tools can still execute while UI is flooded).

---

### 4 — MEDIUM: Layout cost of `AutoSizeMessageArea` under growth

**Where:** `ChatMessageListPanel.AutoSizeMessageArea.getPreferredSize` (`~L256–262`)

Calling `setSize(width, Int.MAX_VALUE)` during preferred-size calculation on every revalidate makes each delta O(text length) in layout work. Combined with FM‑1/FM‑2 → super-linear RAM/CPU.

**Evidence strength:** Confirmed pattern; secondary amplifier, not root cause of wrong text.

---

### 5 — LOW / secondary: Fragile `extractDeltaContent` matching

**Where:** `GabSseAccumulator.extractDeltaContent` (`~L138–149`) — first `"content"` after first `"delta"` via `indexOf`, not a real JSON walk.

**Risks:** Nested `"content"` inside tool-call `arguments` strings, or non-string content arrays, could mis-extract or skip. Unlikely primary cause of “first token spam,” but worth hardening later.

**Evidence strength:** Structural risk only; no fixture proof.

---

### Not primary causes (ruled down)

| Hypothesis | Assessment |
|------------|------------|
| Double-append UI from accumulator + event | `acceptChunk` and `SseEvent.Delta` share the same extract for different consumers (final vs live). UI is only fed via `onStreamDelta` once per event in `GabClient.chatCompletionStreaming` (`~L197–199`). Not double UI append. |
| Auth / wrong endpoint | User reached Grok Build; headers in `applyProviderAuth` are out of scope for this thrash. |
| Infinite agent loop alone | `MAX_ITERATIONS = 20`; cancel path exists. Spam is stream/UI, not an unbounded loop by itself. |
| Responses API needed | Non-goal; chat/completions SSE consumer can be hardened first. |

---

## Data path (end-to-end)

```text
GabClient.chatCompletionStreaming
  └─ processSseLine → SseEvent.Delta(text)     // raw extract, no merge
       └─ onStreamDelta(text)                  // GabClient L199
            └─ AgentSession → onStreamDelta    // L84–86
                 └─ invokeLater per token      // ToolWindowPanel L857–860
                      └─ appendStreamingDelta  // append + full revalidate
  └─ accumulator.acceptChunk → content.append  // same raw extract
```

---

## Exact functions / files to change

### WO-02 (SSE core)

| File | Functions / changes |
|------|---------------------|
| **New** `src/main/kotlin/com/waryway/gab/client/StreamContentMerger.kt` (or under `client/stream/`) | Pure `merge` / `classify` helper |
| `GabSseAccumulator.kt` | `acceptChunk`: merge into `content` instead of blind `append`; emit **true append delta** (or full merged text policy) for `SseEvent.Delta` so UI does not re-append snapshots |
| `GabSseAccumulator.processSseLine` / `deltaFromChunk` | Prefer merger-aware emission: e.g. compute `previous`, merge, emit only `merged.removePrefix(previous)` when non-empty **or** emit snapshot events and let UI set text — **recommend single policy: merger owns accumulation; live events are true append slices after merge** |
| `GabClient.chatCompletionStreaming` | Keep calling `onStreamDelta` with merger-safe event text only (no change if accumulator/processSseLine fixed) |
| **New tests** `StreamContentMergerTest.kt` + extend `GabSseAccumulatorTest.kt` | See test matrix below |

### WO-03 (UI coalesce)

| File | Functions / changes |
|------|---------------------|
| **New** `src/main/kotlin/com/waryway/gab/ui/StreamUiCoalescer.kt` (or `ui/stream/`) | Time/char batch; single pending EDT apply |
| `WarywayGabToolWindowPanel.kt` | Wire coalescer in send path (`onStreamDelta` / `onStreamStart`); flush on complete/error/cancel |
| `ChatMessageListPanel.kt` | `appendStreamingDelta` / `setStreamingBody` / `beginStreamingBody`; throttle `refreshAfterTextChange` / scroll; optional safer `getPreferredSize` |
| `AgentSession.kt` | Call `onStreamStart()` **every** iteration (or before each `chatCompletion`), not only `iterations == 1` |
| **New tests** pure coalescer unit tests (no Swing if possible) | Interval/char flush, reset, trailing flush |

---

## Recommended APIs

### `StreamContentMerger` (WO-02)

```kotlin
object StreamContentMerger {
    enum class Kind { EMPTY, APPEND, SNAPSHOT_EXTEND, SNAPSHOT_REWIND, REPLACE }

    data class Result(
        val kind: Kind,
        /** Full text after merge */
        val fullText: String,
        /** Slice safe to append to a live buffer that already holds the previous full text */
        val appendSlice: String
    )

    /**
     * previous: accumulated full content so far (may be empty).
     * incoming: raw delta.content from one SSE chunk (may be empty / cumulative).
     */
    fun merge(previous: String, incoming: String): Result
}
```

**Suggested rules (minimal, testable):**

1. `incoming` blank → `EMPTY`, fullText = previous, appendSlice = `""`
2. `previous` empty → `APPEND` (or treat as seed), fullText = incoming, appendSlice = incoming
3. `incoming.startsWith(previous) && incoming.length > previous.length` → `SNAPSHOT_EXTEND`, fullText = incoming, appendSlice = incoming.removePrefix(previous)
4. `previous.startsWith(incoming) && incoming.length < previous.length` → `SNAPSHOT_REWIND` (stale shorter replay), keep previous, appendSlice = `""`
5. Else → `APPEND`, fullText = previous + incoming, appendSlice = incoming

**Wire-in:** In `acceptChunk` / event path, keep one `StringBuilder` (or `var full`) updated via `merge`; fire `SseEvent.Delta(appendSlice)` only when `appendSlice.isNotEmpty()`. Final `toResult()` uses full text once.

**False-positive note:** True multi-char tokens that extend as a prefix of the full previous string are rare; document and test edge cases (`"I"` + `"I'm"` after empty is fine; `"cat"` + true delta `"astrophe"` appends; `"cat"` + snapshot `"catastrophe"` extends). Prefer this over regex JSON rewrite.

### `StreamUiCoalescer` (WO-03)

```kotlin
class StreamUiCoalescer(
    private val minIntervalMs: Long = 40L,
    private val minChars: Int = 24,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Schedule a single EDT flush; coalescer must not stack one runnable per token */
    private val schedule: (run: () -> Unit) -> Unit,
    private val onFlush: (pendingAppend: String) -> Unit
) {
    fun accept(appendSlice: String)   // may batch; schedule at most one pending flush
    fun flush()                       // force pending out (turn end / error / cancel)
    fun reset()                       // clear pending + timers between iterations
}
```

**Semantics:**

- Buffer append slices; schedule **one** `schedule { flushInternal() }` if none pending
- Flush when `elapsed >= minIntervalMs` **or** `pending.length >= minChars` (whichever first after schedule)
- `onFlush` receives **joined pending text once**; UI does single `append` + single revalidate
- Alternative (also fine): coalescer holds **full display text** from merger and calls `setStreamingBody(full)` once per flush — simpler if UI switches to absolute set

**UI panel tweaks (same WO):**

- `beginStreamingBody()` on every stream start (AgentSession every iteration)
- Throttle `scrollToBottom` (e.g. only on flush, or max ~10 Hz)
- Optional hard cap: if `streamingBody.length > N` (e.g. 200_000), stop appending and show `"(stream truncated for UI)"`
- Soften `getPreferredSize` if cheap: avoid `Int.MAX_VALUE` thrash; measure only when text/width changes

---

## Minimal fix plan by work order

### WO-02 — SSE core

1. Add pure `StreamContentMerger` + unit tests (matrix below).
2. Integrate into `GabSseAccumulator.acceptChunk` and `processSseLine` event emission so **both** final content and live deltas are snapshot-safe.
3. Extend `GabSseAccumulatorTest` with cumulative snapshots + mixed tool-call streams.
4. Do **not** touch Swing in WO-02.

**Test matrix (WO-02):**

| Case | Input sequence | Expected full |
|------|----------------|---------------|
| Normal deltas | `"Hel"`, `"lo"` | `"Hello"` |
| Snapshots | `"H"`, `"He"`, `"Hel"`, `"Hello"` | `"Hello"` (not quadratic) |
| Empty / null-ish | `""` ignored | previous unchanged |
| After snapshot, true delta | `"Hello"`, `"!"` | `"Hello!"` |
| Shorter rewind | `"Hello"`, `"Hel"` | `"Hello"` |
| Tool-call-only chunks | no content | content null/empty; tools intact |

### WO-03 — UI coalesce

1. Add `StreamUiCoalescer`; unit-test batching without real Swing (fake `schedule` / clock).
2. In `WarywayGabToolWindowPanel` send path: create coalescer per turn; `onStreamDelta` → `coalescer.accept`; complete/error/cancel → `flush()`; stream start / iteration → `reset()` + `beginStreamingBody()`.
3. Fix `AgentSession` to invoke `onStreamStart()` each iteration before `chatCompletion`.
4. Reduce per-delta layout: only revalidate/scroll on coalesced flush; optional preferred-size guard + display length cap.
5. Keep merger out of UI if WO-02 already emits safe append slices (UI just displays).

### WO-04 — package (out of diagnose scope)

Version bump, CHANGELOG / HOW_TO_LOAD note, STATUS.

---

## Risks / non-goals

### Risks

- **False snapshot detection** on rare true deltas that prefix-extend full previous text — mitigate with tests and keep rules simple.
- **Coalescer latency** (~40ms) makes streaming feel slightly less “live”; acceptable.
- **Absolute set vs append in UI** if WO-02 and WO-03 diverge — pick one: merger produces append slices; UI only appends coalesced slices.
- **Incomplete diagnosis of wire format** without a saved Grok Build SSE capture — still safe to implement merger (no-op for true deltas).
- Agent completion may already work while UI dies; after fix, re-check tool rounds + final content vs activity log.

### Non-goals (do not change in this fix train)

- Grok Build auth / `cli-chat-proxy` headers / `grok login`
- Switch to Responses API (`/v1/responses`)
- Full markdown chat renderer
- Local LLM `/api/agent` path
- Changing tool registry / MCP execution semantics
- Silent drop of `SseEvent.Error` / `streamError` handling
- Production Kotlin edits from **this** WO (diagnose only — done)

### What not to “fix” as root cause

- Blaming only the model for repeating text without fixing append + EDT
- Adding sleeps in the SSE read loop instead of merger + coalesce
- Disabling streaming entirely (masks bug; worse UX / timeouts)

---

## Agent completion uncertainty (user Q)

| Layer | Likely state during thrash |
|-------|----------------------------|
| HTTP SSE read | Continues on background thread until cancel/error/DONE |
| `AgentSession` tools | Can still run; status lines also queue on EDT (may lag) |
| Chat UI | Unreliable display; `streamingBody` may be garbage if FM‑1 |
| Stored conversation | `completeAgentTurn` / `conv.addMessage` use session result; final content may still be **wrong** if accumulator suffered FM‑1 |

So: **tools may have run**, but **correctness of stored assistant text is not guaranteed** until WO-02 lands.

---

## Deliverable checklist

- [x] Confirmed failure modes (ranked)
- [x] Exact functions/files to change
- [x] Recommended APIs for StreamContentMerger / StreamUiCoalescer
- [x] Risks / non-goals
- [x] Minimal fix plan for WO-02 / WO-03
- [x] No production Kotlin edits (read-only)

**WO-01 complete.** Hand off to WO-02 (stream-core) then WO-03 (ui-coalesce).

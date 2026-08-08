# Plan: Grok Build stream fix

## Root-cause hypotheses (ordered)

1. **EDT flood** — `onStreamDelta` → `SwingUtilities.invokeLater` per token + full `JTextArea` rebuild + `revalidate`/`repaint`/`scrollToBottom` → RAM + “same text forever” feel.
2. **Snapshot-as-delta** — proxy may re-send full content (or full-so-far) in `delta.content`; naive `append` produces quadratic growth and the first token appears at the start of every frame.
3. **Agent loop re-stream without clear** — retries / multi-iteration continue appending into the same turn buffer without resetting the live body.
4. **Layout cost** — `AutoSizeMessageArea.getPreferredSize` calling `setSize(width, Int.MAX_VALUE)` on every revalidate amplifies cost as text grows.

## Sections / work orders

| ID | Owner | Work |
|----|-------|------|
| WO-01 | diagnose | Confirm code paths; document exact failure modes; recommend minimal fix set |
| WO-02 | stream-core | SSE snapshot detection + safe content accumulation; tests |
| WO-03 | ui-coalesce | Coalesce stream deltas to EDT; throttle layout; clear between iterations |
| WO-04 | package | Version bump, CHANGELOG, STATUS, smoke test command |

## Implementation notes for workers

- Prefer pure helpers unit-tested without Swing where possible.
- Keep Grok Build auth headers / chat/completions path; harden consumer side first.
- Do not silently drop stream errors.
- Cap display growth with a clear “(stream truncated for UI)” note if a hard safety cap is hit.

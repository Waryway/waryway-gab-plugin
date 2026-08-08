# Directive: Grok Build stream / UI reliability fix

**Project slug:** `grok-build-stream-fix`  
**Date:** 2026-07-17  
**Status:** in progress  

## Problem (user report)

After updating the plugin to use **Grok Build** (`cli-chat-proxy` / `grok login` session):

1. The plugin **did** reach Grok Build (auth/path worked).
2. **Only the first text string** from Grok appeared in the plugin chat window.
3. That string was **printed / re-rendered a huge number of times**, RAM spiked hard.
4. Unclear whether the agent actually completed the prompted work (tools / edits).

## Goals

1. **Stop runaway UI / memory** when streaming from Grok Build (or any OpenAI-compatible SSE backend).
2. **Correct stream accumulation**: true deltas append; cumulative snapshots replace (never explode).
3. **Coalesce EDT updates** so high-frequency token deltas do not queue millions of `invokeLater` layout passes.
4. **Preserve agent usefulness**: tools, stop, final content, activity log still work; user can see progress without thrash.
5. **Tests** covering snapshot-vs-delta, coalescing caps, and non-regression of normal delta streams.
6. **Markdown coordination** under `.grok/org/grok-build-stream-fix/` — subagents report status in tickets, not only chat.

## Non-goals

- Rewrite to Responses API (`/v1/responses`) in this pass (document as follow-up if needed).
- Full markdown chat renderer rewrite.
- New features beyond stream reliability.

## Success criteria

- [ ] Normal OpenAI-style deltas still produce correct full text once.
- [ ] Cumulative/snapshot chunks do not append into quadratic / million-repeat text.
- [ ] Stream UI updates are coalesced (time and/or char batch); no per-token full revalidate storm.
- [ ] Unit tests green for SSE + any new coalescer helpers.
- [ ] Version bump so a fresh Install-from-Disk zip is distinguishable.
- [ ] Short note in CHANGELOG / HOW_TO_LOAD on what was fixed.

## Coordination

| Artifact | Path |
|----------|------|
| Plan | `plan.md` |
| Section tickets | `sections/` |
| Work orders | `work-orders/` |
| Status board | `STATUS.md` |

Subagents must update their work-order MD with: findings, files changed, test commands, remaining risks.

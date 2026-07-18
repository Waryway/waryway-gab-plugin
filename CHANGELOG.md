## [Unreleased]

### Added
- **Stop that actually unblocks:** Stop closes the live SSE body (and interrupts the worker thread) so cancel is not stuck waiting on the next chunk; Stop is also next to Send.
- **Fail package export:** Activity log → **Export fail** writes a Markdown package (trigger, model/provider, last Q/A, full activity log, paths) under `.waryway-gab/logs/`. Path is shown in chat and copied to the clipboard.
- **Durable session log file:** every session appends to a file under `.waryway-gab/logs/session_*.log`; path is shown under the Activity log (copy-path button).
- Auto fail package on user Stop, request/agent failures, max-iterations / stuck terminals.

### Fixed
- **Chat dump loops:** status lines in the agent bubble soft-capped (full detail stays in Activity log / fail package); ERR lines mirrored to chat capped per turn.
- **0.1.28 Multi-turn chat:** Stopped re-persisting the user message after each agent turn (system prompt prepend shifted indices → duplicate “You” bubbles).
- **0.1.28 Tab bar:** Programmatic history/title refresh no longer fires conversation switch / reloads the message list mid-turn.
- **0.1.28 Send:** Atomic in-flight guard so Enter cannot start two concurrent turns.
- **0.1.28 Display:** Conversation reload maps only user + final assistant text (hides tool-call internals).
- **0.1.27 Grok Build stream fix:** Snapshot-safe SSE merge via `StreamContentMerger` — stops cumulative chunks from quadratic-append RAM death.
- Stream UI coalescer (~40ms) + scroll throttle + soft cap to keep the chat pane responsive under high-frequency deltas.
- `AgentSession` resets stream body each completion iteration so multi-step agent turns do not accumulate stale stream state.

### Changed
- **0.1.24** packaging stamp: rebuild after AgentClient / LocalLlmAgentSession sources so Install-from-Disk is not stuck on chat-only.
- Document known-bad **0.1.23 (2026-06-29)** zip; prefer `runIde` or fresh `buildPlugin` zip. See `HOW_TO_LOAD_IN_IDE.md`.

## [0.1.30]

Grok Build interaction polish (beyond stream-only 0.1.27 builds). Prefer Install-from-Disk of **0.1.30+** or `runIde` after rebuild.

### Added / Improved
- **Grok Build session recovery:** detect missing or expired coaching and refresh the session without an IDE restart.
- **Actionable chat/send failures:** map auth and network failures to clear operator-facing messages instead of bare HTTP text.
- **Tool-window failure UX:** clearer Grok Build error presentation and a session status chip in the tool window.
- **Local LLM `/api/agent` terminal fidelity:** cancel, timeout, and empty `finalAnswer` paths behave consistently at the agent boundary.

### Fixed / Guarded
- **Stream merge contracts:** regression coverage keeps snapshot-safe SSE merge (`StreamContentMerger`) locked so cumulative chunks do not reintroduce quadratic-append / UI thrash.

## [0.1.0]

### Added
- Initial release of Waryway Gab plugin for GoLand / IntelliJ.
- Gab AI chat tool window.
- Settings for API key.
- Basic client for Gab API.

[0.1.0]: https://github.com/waryway/waryway-gab-plugin/releases/tag/v0.1.0

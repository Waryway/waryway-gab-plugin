# Waryway Gab Plugin for JetBrains IDEs

Agentic AI chat sidebar for GoLand / JetBrains IDEs using the Gab AI API.

## Goals
- Right-side tool window for chatting with Gab AI
- Model + Thinking level selection
- Skills with dynamic forms
- Full streaming + agentic tool-calling loops
- Rich workspace context (files, selections, problems)
- Safe code editing with review/apply
- Prominent token + credit + context usage tracking

## Requirements
- Gab AI Plus subscription (for API access)
- JetBrains IDE 2024.3+ (GoLand recommended)

## Getting Started (Development)

1. Clone this repo (plugin root often `C:\dev\waryway-gab-plugin`).
2. Open in IntelliJ IDEA / GoLand.
3. Let Gradle sync (it will download the wrapper + dependencies).
4. Prefer **`.\gradlew.bat runIde`** so the sandbox loads current agent classes (`AgentClient`, `LocalLlmAgentSession`).
5. Or **`.\gradlew.bat buildPlugin`** → install `build/distributions/waryway-gab-plugin-*.zip` via Settings → Plugins → Install Plugin from Disk.
6. The **Waryway Agent** tool window will appear on the right.

**Version:** see `pluginVersion` in `gradle.properties` (currently **0.1.30**). Includes Grok Build session recovery and failure UX (not stream-only 0.1.27).  
**Do not install** the stale **`0.1.23` zip dated 2026-06-29** — it lacks agent classes (chat-only). Details: [HOW_TO_LOAD_IN_IDE.md](./HOW_TO_LOAD_IN_IDE.md).

## Configuration
- Open **Settings > Tools > Waryway Gab** (or use the coaching UI in the tool window).
- Paste your Gab AI API key (from https://gab.ai → Settings → API Settings).

## Current Status
This is an early implementation following [PLAN.md](./PLAN.md).

Phases completed (core for "load into IDE"):
- Project skeleton (Gradle + intellij-platform 2.x)
- Right-side Tool Window
- Unauthenticated coaching UI + easy key paste + test
- Basic settings (API key via PasswordSafe)
- Stub chat UI + simulated streaming

**Current status**: The plugin is loadable and runnable in GoLand (see [HOW_TO_LOAD_IN_IDE.md](./HOW_TO_LOAD_IN_IDE.md)).

Next steps are tracked in [UPDATED_PLAN.md](./UPDATED_PLAN.md) — start with Phase 2a (Streaming + Basic Agent Loop).

## License
Internal / TBD.

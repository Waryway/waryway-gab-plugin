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

**Version:** see `pluginVersion` in `gradle.properties` (currently **0.1.46**). Local LLM answer/thinking split; tool-window offline hardening; timeout/partial recovery; skills in prompt/composer; layout maximize; collapsible shell/command output; Grok Build session identity; MCP/native IDE tools when MCP is off. Details: [HOW_TO_LOAD_IN_IDE.md](./HOW_TO_LOAD_IN_IDE.md).  
**Do not install** the stale **`0.1.23` zip dated 2026-06-29** — it lacks agent classes (chat-only).

## Configuration
- Open **Settings > Tools > Waryway Gab** (or use the coaching UI in the tool window).
- Paste your Gab AI API key (from https://gab.ai → Settings → API Settings), or use **Grok Build** / **Local LLM** as documented in [HOW_TO_LOAD_IN_IDE.md](./HOW_TO_LOAD_IN_IDE.md).

### GoLand MCP tools (cloud chat agent)
Cloud providers (Grok Build, Gab, Grok API) use JetBrains **MCP Server** for IDE tools. Enable **Settings → Tools → MCP Server**. The plugin auto-discovers `127.0.0.1:63342–63352` (or **IDE MCP Port** / `IDE_PORT`) and **caches** the result so each Send does not re-scan ports. Details: [HOW_TO_LOAD_IN_IDE.md](./HOW_TO_LOAD_IN_IDE.md#goland-mcp-optional-cloud--local-mcp-tools).

## Current Status
Loadable agentic plugin for GoLand (see [HOW_TO_LOAD_IN_IDE.md](./HOW_TO_LOAD_IN_IDE.md)).

Working surfaces:
- Right-side **Waryway Agent** tool window + multi-provider settings
- Grok Build / Gab / Grok API streaming chat + agent loop
- Local LLM chat + optional `/api/agent` workbench mode
- GoLand built-in MCP for IDE tools (cached discovery)
- Session file logs under `.waryway-gab/logs/`

Original phased plan: [PLAN.md](./PLAN.md) / [UPDATED_PLAN.md](./UPDATED_PLAN.md).

## License
Internal / TBD.

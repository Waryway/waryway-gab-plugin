# How to load Waryway Agent plugin into your IDE

1. Open the folder `C:\dev\waryway-gab-plugin` in **GoLand** (or IntelliJ IDEA / other JetBrains IDE 2024.3+).

2. Wait for Gradle sync to complete.
   - If it complains about Gradle JVM, go to:
     Settings → Build, Execution, Deployment → Build Tools → Gradle
   - Set **Gradle JVM** to a bundled **Java 17** or **Java 21** runtime (the IDE usually lists several JBR options).
   - Click "OK" and let it re-sync.

3. In the Gradle tool window, expand `waryway-gab-plugin` → `Tasks` → `intellij`.
   - Double-click `runIde` (or right-click → Run).

4. A sandbox IDE will start with the plugin loaded.
   - Open the **right tool window bar**.
   - You should see **"Waryway Agent"**.

5. First run experience:
   - Without an API key it shows a coaching screen with steps + a paste field.
   - Paste a Gab AI Plus API key (from https://gab.ai → Settings → API Settings).
   - Click "Use Key & Continue" or "Test Key".
   - The chat UI appears.
   - **Local LLM** is the default provider (offline). Start `scripts\localllm-run.bat` in the stack repo, then select **Local LLM** and model `localllm-coder`.
   - For cloud: switch provider to **Grok** (`grok-4.3`) or **Gab AI** (`gpt-5`) and add an API key.

## Alternative: Build the plugin artifact
From Gradle tool window:
- `buildPlugin` produces `build/distributions/Waryway-Agent-*.zip`
- You can install it via Settings → Plugins → Install Plugin from Disk...

## Notes
- **GoLand MCP Server is required for IDE tools.** Enable it: Settings → Tools → MCP Server → Enable MCP Server. Optional: enable brave mode for terminal commands without confirmation.
- The plugin calls the built-in MCP HTTP API (`http://127.0.0.1:{port}/api/mcp/*`). Tool schemas are loaded live from `list_tools`, with `mcps/goland1/tools/*.json` as fallback.
- **Cloud providers** (Grok, Gab AI) use MCP tools via function calling when the MCP server is reachable.
- **Local LLM** talks to `http://127.0.0.1:7400/v1` (OpenAI-compatible). Uses the `gab-chat` preset. MCP tools are off by default for local models — enable in Settings → Waryway Agent → "Enable MCP tools for Local LLM".
- Grok uses `https://api.x.ai/v1`. Gab AI uses `https://gab.ai/v1`.
- Streaming SSE and skills are still planned (see UPDATED_PLAN.md).

Enjoy building with Waryway Agent!
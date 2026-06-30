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
- The `mcps/goland1` folder documents JetBrains MCP tool shapes; the plugin implements equivalent tools natively via IntelliJ APIs.
- **Cloud providers** send IDE tools via function calling (`read_file`, `search_text`, `replace_text_in_file`, `write_file`, `run_shell_command`, etc.).
- **Local LLM** talks to `http://127.0.0.1:7400/v1` (OpenAI-compatible). Uses the `goland` prompt preset; chat mode without tool schema to fit small GGUF models.
- Grok uses `https://api.x.ai/v1`. Gab AI uses `https://gab.ai/v1`.
- Streaming SSE and skills are still planned (see UPDATED_PLAN.md).

Enjoy building with Waryway Agent!
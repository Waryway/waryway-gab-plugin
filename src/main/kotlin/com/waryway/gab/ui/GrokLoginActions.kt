package com.waryway.gab.ui

import com.intellij.ide.BrowserUtil
import com.waryway.gab.model.ModelProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opens the Grok Build login help page and, when possible, starts `grok login`
 * so the OAuth browser flow actually appears (instead of only showing text coaching).
 */
object GrokLoginActions {

    const val LOGIN_HELP_URL: String = "https://x.ai/cli"
    const val LOGIN_CMD: String = "grok login"

    data class Result(
        val browserOpened: Boolean,
        val processStarted: Boolean,
        val message: String
    )

    /**
     * Always tries to open the login help URL in the system browser.
     * Best-effort: also launches `grok login` when the CLI is on PATH or known install locations.
     */
    fun openLoginFlow(): Result {
        var browserOk = false
        try {
            BrowserUtil.browse(LOGIN_HELP_URL)
            browserOk = true
        } catch (_: Exception) {
            try {
                BrowserUtil.browse(ModelProvider.GROK_BUILD.keyHelpUrl)
                browserOk = true
            } catch (_: Exception) {
            }
        }

        val cli = findGrokCli()
        var processOk = false
        if (cli != null) {
            try {
                val pb = ProcessBuilder(cli + listOf("login"))
                    .redirectErrorStream(true)
                    .directory(File(System.getProperty("user.home")))
                // Detach — OAuth is interactive in the browser / terminal.
                pb.start()
                processOk = true
            } catch (_: Exception) {
                processOk = false
            }
        }

        val message = buildString {
            if (browserOk) append("Opened login page: $LOGIN_HELP_URL. ")
            else append("Could not open browser. Visit $LOGIN_HELP_URL. ")
            if (processOk) {
                append("Started `$LOGIN_CMD` in the background — complete sign-in in the browser, then click the session badge to re-check.")
            } else {
                append("Run `$LOGIN_CMD` in a terminal if the CLI did not start, then click the session badge to re-check.")
            }
        }
        return Result(browserOpened = browserOk, processStarted = processOk, message = message.trim())
    }

    fun openUrl(url: String): Boolean = try {
        BrowserUtil.browse(url)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Resolve `grok` executable: PATH first, then common install locations on this machine.
     */
    fun findGrokCli(): List<String>? {
        val pathHit = findOnPath("grok")
        if (pathHit != null) return listOf(pathHit)

        val home = System.getProperty("user.home") ?: return null
        val candidates = listOf(
            File(home, ".grok/bin/grok.exe"),
            File(home, ".grok/bin/grok"),
            File(home, ".local/bin/grok")
        )
        for (f in candidates) {
            if (f.isFile && f.canExecute()) return listOf(f.absolutePath)
        }
        // Windows: try `where grok` via cmd when PATH resolution above fails in the plugin JVM.
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            try {
                val p = ProcessBuilder("where.exe", "grok")
                    .redirectErrorStream(true)
                    .start()
                if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    val line = p.inputStream.bufferedReader().readLine()?.trim()
                    if (!line.isNullOrBlank() && File(line).isFile) return listOf(line)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun findOnPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        val isWin = System.getProperty("os.name", "").lowercase().contains("win")
        val extensions = if (isWin) listOf("", ".exe", ".cmd", ".bat", ".ps1") else listOf("")
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (ext in extensions) {
                val f = File(dir, name + ext)
                if (f.isFile) return f.absolutePath
            }
        }
        return null
    }
}

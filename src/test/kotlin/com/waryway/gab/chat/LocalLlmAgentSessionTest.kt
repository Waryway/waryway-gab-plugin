package com.waryway.gab.chat

import com.waryway.gab.client.AgentClient
import com.waryway.gab.model.ContextAttachment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for Local LLM agent path helpers:
 * - Progress stream formatting (wo-01-02)
 * - Terminal finish/status formatting (wo-03-01)
 * - Cancel / timeout poll-loop seam with fake [AgentRunOps] (wo-03-02)
 * - Attachment path capture + goal builder
 */
class LocalLlmAgentSessionTest {

    private fun run(
        id: String = "run-test",
        state: String = "done",
        dryRun: Boolean = true,
        finalAnswer: String? = null,
        error: String? = null,
        repoRoot: String = "",
        step: Int = 0,
        maxSteps: Int = 0,
        message: String? = null,
        events: List<AgentClient.AgentEvent> = emptyList(),
        goal: String = "goal",
        plan: AgentClient.AgentPlan? = null
    ) = AgentClient.AgentRun(
        id = id,
        goal = goal,
        state = state,
        dryRun = dryRun,
        finalAnswer = finalAnswer,
        error = error,
        repoRoot = repoRoot,
        step = step,
        maxSteps = maxSteps,
        message = message,
        events = events,
        plan = plan
    )

    /**
     * In-memory [AgentRunOps] for poll-loop tests — no HTTP.
     * Scripts start → poll sequence; cancel can succeed, throw, or rewrite state.
     */
    private class FakeAgentOps(
        private val startSnapshot: AgentClient.AgentRun,
        private val pollSnapshots: List<AgentClient.AgentRun> = emptyList(),
        private val cancelResult: (() -> AgentClient.AgentRun)? = null,
        private val cancelThrows: Boolean = false
    ) : AgentRunOps {
        val cancelIds = CopyOnWriteArrayList<String>()
        val getIds = CopyOnWriteArrayList<String>()
        val getQuietFlags = CopyOnWriteArrayList<Boolean>()
        private val pollIndex = AtomicInteger(0)
        private val lastSnap = AtomicReference(startSnapshot)

        override fun startRun(
            goal: String,
            dryRun: Boolean,
            preset: String?,
            model: String?,
            maxSteps: Int?
        ): AgentClient.AgentRun {
            lastSnap.set(startSnapshot)
            return startSnapshot
        }

        override fun getRun(id: String, quiet: Boolean): AgentClient.AgentRun {
            getIds.add(id)
            getQuietFlags.add(quiet)
            val idx = pollIndex.getAndIncrement()
            val snap = if (idx < pollSnapshots.size) pollSnapshots[idx] else {
                // Stay non-terminal so cancel/timeout paths can fire without server finish.
                lastSnap.get().copy(state = "running", id = id)
            }
            lastSnap.set(snap)
            return snap
        }

        override fun cancelRun(id: String): AgentClient.AgentRun {
            cancelIds.add(id)
            if (cancelThrows) throw RuntimeException("cancel transport failed")
            val result = cancelResult?.invoke()
                ?: lastSnap.get().copy(id = id, state = "cancelled")
            lastSnap.set(result)
            return result
        }
    }

    // --- cancel / timeout poll loop with fake AgentRunOps (wo-03-02) ---

    @Test
    fun `run mid-run cancelActiveRun finishes with non-blank content and clears activeRunId`() {
        val started = run(id = "run-cancel-1", state = "planning", dryRun = true)
        val fake = FakeAgentOps(startSnapshot = started)
        val cancelledFlag = AtomicBoolean(false)
        val statuses = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            cancelled = cancelledFlag,
            pollIntervalMs = 15L,
            timeoutMs = 5_000L,
            onStatus = { statuses.add(it) },
            onLogLine = { logs.add(it) }
        )

        val resultBox = AtomicReference<LocalLlmAgentSession.Result?>(null)
        val errorBox = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                val result = session.run(goal = "cancel me", dryRun = true)
                resultBox.set(result)
            } catch (t: Throwable) {
                errorBox.set(t)
            }
        }
        // Poll until currentRunId is set, then cancel from "UI" thread.
        worker.start()
        val deadline = System.currentTimeMillis() + 2_000L
        while (session.currentRunId() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertNotNull(session.currentRunId(), "expected active run after start")
        session.cancelActiveRun()
        worker.join(3_000)
        assertFalse(worker.isAlive, "poll loop should have exited after cancel")

        assertNull(errorBox.get(), "unexpected: ${errorBox.get()}")
        val result = assertNotNull(resultBox.get())
        assertTrue(result.finalContent.isNotBlank())
        assertTrue(
            result.finalContent.contains("Stopped by user") ||
                result.finalContent.contains("Halfway") ||
                result.run.state == "cancelled" ||
                cancelledFlag.get(),
            "content=${result.finalContent} state=${result.run.state}"
        )
        assertNull(session.currentRunId(), "activeRunId must be cleared on finish")
        assertTrue(fake.cancelIds.isNotEmpty(), "cancelRun must be posted for active id")
        assertEquals("run-cancel-1", fake.cancelIds.first())
        assertTrue(
            statuses.any { it.contains("cancelled", ignoreCase = true) } ||
                logs.any { it.contains("cancelled", ignoreCase = true) },
            "status/log should mention cancel; statuses=$statuses logs=$logs"
        )
    }

    @Test
    fun `run cancel when cancelRun throws still finishes with non-blank content`() {
        val started = run(id = "run-cancel-fail", state = "executing", dryRun = false)
        val fake = FakeAgentOps(startSnapshot = started, cancelThrows = true)
        val cancelledFlag = AtomicBoolean(false)
        val session = LocalLlmAgentSession(
            ops = fake,
            cancelled = cancelledFlag,
            pollIntervalMs = 15L,
            timeoutMs = 5_000L
        )

        val resultBox = AtomicReference<LocalLlmAgentSession.Result?>(null)
        val errorBox = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                resultBox.set(session.run(goal = "stop despite cancel error", dryRun = false))
            } catch (t: Throwable) {
                errorBox.set(t)
            }
        }
        worker.start()
        val deadline = System.currentTimeMillis() + 2_000L
        while (session.currentRunId() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        session.cancelActiveRun()
        worker.join(3_000)

        assertNull(errorBox.get(), "cancel transport failure must not abort finish: ${errorBox.get()}")
        val result = assertNotNull(resultBox.get())
        assertTrue(result.finalContent.isNotBlank())
        assertEquals(LocalLlmAgentSession.STOPPED_BY_USER, result.finalContent)
        assertNull(session.currentRunId())
        assertTrue(fake.cancelIds.isNotEmpty())
    }

    @Test
    fun `run timeout cancels server run and throws AgentTimeoutException`() {
        val started = run(id = "run-timeout-1", state = "running", dryRun = true)
        val fake = FakeAgentOps(startSnapshot = started)
        val logs = CopyOnWriteArrayList<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 10L,
            timeoutMs = 40L,
            stallTimeoutMs = 40L,
            onLogLine = { logs.add(it) }
        )

        val ex = assertFailsWith<LocalLlmAgentSession.AgentTimeoutException> {
            session.run(goal = "slow run", dryRun = true)
        }
        assertTrue(ex.message!!.contains("timed out"), ex.message)
        assertTrue(ex.message!!.contains("timeout", ignoreCase = true) ||
            ex.message!!.contains("timed out"), ex.message)
        // Distinct from offline / HTTP connection wording
        assertFalse(ex.message!!.contains("Connection refused", ignoreCase = true))
        assertFalse(ex.message!!.contains("unreachable", ignoreCase = true))
        assertEquals("run-timeout-1", ex.runId)
        assertTrue(fake.cancelIds.contains("run-timeout-1"), "timeout must post cancelRun")
        assertNull(session.currentRunId(), "activeRunId cleared on timeout")
        assertTrue(logs.any { it.contains("timed out") }, logs.toString())
    }

    @Test
    fun `run immediate terminal start finishes via finish path without poll`() {
        val started = run(
            id = "run-instant",
            state = "done",
            dryRun = true,
            finalAnswer = "Already finished",
            repoRoot = "/repo"
        )
        val fake = FakeAgentOps(startSnapshot = started)
        val statuses = CopyOnWriteArrayList<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 50L,
            timeoutMs = 1_000L,
            onStatus = { statuses.add(it) }
        )

        val result = session.run(goal = "instant", dryRun = true)
        assertTrue(result.finalContent.contains("Already finished"), result.finalContent)
        assertTrue(result.finalContent.contains("### Answer"), result.finalContent)
        assertTrue(result.finalContent.contains("Dry-run"))
        assertEquals("done", result.run.state)
        assertNull(session.currentRunId())
        assertTrue(fake.getIds.isEmpty(), "immediate terminal must not poll getRun")
        assertTrue(fake.cancelIds.isEmpty())
        assertTrue(statuses.any { it.contains("done") }, statuses.toString())
    }

    @Test
    fun `run immediate failed start still finishes with Error content`() {
        val started = run(
            id = "run-fail-instant",
            state = "failed",
            dryRun = true,
            error = "planner exploded"
        )
        val fake = FakeAgentOps(startSnapshot = started)
        val session = LocalLlmAgentSession(ops = fake, pollIntervalMs = 20L, timeoutMs = 500L)
        val result = session.run(goal = "fail fast", dryRun = true)
        assertEquals("Error: planner exploded", result.finalContent)
        assertNull(session.currentRunId())
    }

    // --- formatEventSummary / progress helpers (wo-01-02) ---

    /** Multi-line directory-tree fixture (several files; flattened length > 240). */
    private fun directoryTreeDetail(): String = buildString {
        appendLine(".")
        appendLine("├── AGENTS.md")
        appendLine("├── BUILD.bazel")
        appendLine("├── README.md")
        appendLine("├── src/")
        appendLine("│   ├── main/")
        appendLine("│   │   ├── kotlin/")
        appendLine("│   │   │   └── com/waryway/gab/chat/LocalLlmAgentSession.kt")
        appendLine("│   │   └── resources/")
        appendLine("│   └── test/")
        appendLine("│       └── kotlin/")
        appendLine("│           └── com/waryway/gab/chat/LocalLlmAgentSessionTest.kt")
        appendLine("├── gradle/")
        appendLine("│   └── wrapper/")
        appendLine("│       ├── gradle-wrapper.jar")
        appendLine("│       └── gradle-wrapper.properties")
        appendLine("└── settings.gradle.kts")
        // Pad so a naive space-join would exceed a short progress line and look like a dump.
        repeat(8) { i ->
            appendLine("├── padding/module$i/src/FileWithAReasonablyLongName$i.kt")
        }
    }.trimEnd()

    /** Multi-line search-hit fixture (paths + match context lines). */
    private fun searchHitsDetail(): String = buildString {
        appendLine("src/main/kotlin/com/waryway/gab/chat/LocalLlmAgentSession.kt:120: fun formatEventSummary")
        appendLine("src/main/kotlin/com/waryway/gab/chat/LocalLlmAgentSession.kt:200: progressNonBlankLines")
        appendLine("src/test/kotlin/com/waryway/gab/chat/LocalLlmAgentSessionTest.kt:10: formatEventSummary")
        appendLine("src/main/kotlin/com/waryway/gab/client/AgentClient.kt:53: data class AgentEvent")
        appendLine("apps/retronium/main.go:42: search hit context line with extra padding words")
        appendLine("pkg/store/fsstore.go:88: another match line for multi-line awareness")
        repeat(6) { i ->
            appendLine("hit $i: com/waryway/gab/chat/File$i.kt:$i: keyword match context padding")
        }
    }.trimEnd()

    @Test
    fun `formatEventSummary multi-line directory tree is summary not space-joined dump`() {
        val tree = directoryTreeDetail()
        assertTrue(tree.lines().count { it.isNotBlank() } > 1, "fixture must be multi-line")
        assertTrue(tree.replace('\n', ' ').length > 240, "fixture flattened should exceed 240 chars")

        val ev = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "list_directory_tree",
            detail = tree
        )
        val summary = LocalLlmAgentSession.formatEventSummary(ev)

        assertTrue(summary.contains("tool_result"), summary)
        assertTrue(summary.contains("list_directory_tree"), summary)
        assertTrue(
            summary.contains("lines") && summary.contains("chars"),
            "expected line/char stats: $summary"
        )
        // First-line / snippet presence (tree root ".") without full dump.
        assertTrue(summary.contains("—"), "expected snippet separator: $summary")
        assertTrue(
            summary.length <= LocalLlmAgentSession.PROGRESS_SUMMARY_MAX_CHARS,
            "summary too long (${summary.length}): $summary"
        )
        // Not a single space-joined run-on of the whole tree.
        val spaceJoined = tree.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        assertFalse(summary.contains(spaceJoined), "must not embed full space-joined tree")
        assertFalse(
            summary.contains("LocalLlmAgentSessionTest.kt") && summary.contains("gradle-wrapper.properties"),
            "must not dump many tree paths into the progress line: $summary"
        )
        // Multi-line awareness: line count from helper matches fixture non-blank lines.
        val expectedLines = LocalLlmAgentSession.progressNonBlankLineCount(tree)
        assertTrue(summary.contains("$expectedLines lines"), summary)
    }

    @Test
    fun `formatEventSummary multi-line search hits is summary not jumble`() {
        val hits = searchHitsDetail()
        assertTrue(LocalLlmAgentSession.progressNonBlankLineCount(hits) > 1)

        val ev = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "search_code",
            detail = hits
        )
        val summary = LocalLlmAgentSession.formatEventSummary(ev)

        assertTrue(summary.contains("tool_result"), summary)
        assertTrue(summary.contains("search_code"), summary)
        assertTrue(summary.contains("lines") && summary.contains("chars"), summary)
        assertTrue(summary.contains("—"), "expected first-line snippet: $summary")
        // Snippet should reflect first hit path, not every match.
        assertTrue(
            summary.contains("LocalLlmAgentSession.kt") || summary.contains("formatEventSummary"),
            "expected first-hit snippet: $summary"
        )
        assertTrue(
            summary.length <= LocalLlmAgentSession.PROGRESS_SUMMARY_MAX_CHARS,
            "summary too long: $summary"
        )
        val spaceJoined = hits.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        assertFalse(summary.contains(spaceJoined), "must not embed full space-joined search dump")
        assertFalse(
            summary.contains("fsstore.go") && summary.contains("retronium"),
            "must not jumble many hit paths: $summary"
        )
    }

    @Test
    fun `formatEventSummary short single-line detail stays concise`() {
        val detail = "ok — 3 entries"
        val ev = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "list_directory_tree",
            detail = detail
        )
        val summary = LocalLlmAgentSession.formatEventSummary(ev)

        assertEquals("  tool_result list_directory_tree: $detail", summary)
        assertFalse(summary.contains("lines"), "single-line should not use multi-line stats: $summary")
        assertFalse(summary.contains("…"), "short detail should not truncate: $summary")
        assertTrue(summary.length < 80, summary)
    }

    @Test
    fun `formatEventSummary empty detail has no trailing colon noise`() {
        val blank = LocalLlmAgentSession.formatEventSummary(
            AgentClient.AgentEvent(kind = "tool_result", tool = "read_file", detail = "")
        )
        assertEquals("  tool_result read_file", blank)
        assertFalse(blank.contains(":"), "empty detail must not leave ': ': $blank")

        val whitespace = LocalLlmAgentSession.formatEventSummary(
            AgentClient.AgentEvent(kind = "tool_result", tool = "read_file", detail = "   \n  ")
        )
        assertEquals("  tool_result read_file", whitespace)
        assertFalse(whitespace.endsWith(":"), whitespace)
        assertFalse(whitespace.contains(": "), whitespace)

        val noTool = LocalLlmAgentSession.formatEventSummary(
            AgentClient.AgentEvent(kind = "status", tool = null, detail = "")
        )
        assertEquals("  status", noTool)
    }

    @Test
    fun `formatEventSummary tool_call short path stays readable`() {
        val ev = AgentClient.AgentEvent(
            kind = "tool_call",
            tool = "list_directory_tree",
            detail = "."
        )
        val summary = LocalLlmAgentSession.formatEventSummary(ev)
        assertEquals("  tool_call list_directory_tree: .", summary)
        assertTrue(summary.length < 60, summary)

        val withArgs = LocalLlmAgentSession.formatEventSummary(
            AgentClient.AgentEvent(
                kind = "tool_call",
                tool = "search_code",
                detail = "query=LocalLlm path=src/"
            )
        )
        assertEquals("  tool_call search_code: query=LocalLlm path=src/", withArgs)
        assertFalse(withArgs.contains("lines"), withArgs)
    }

    @Test
    fun `progress helpers nonBlank lines snippet and char count`() {
        val multi = "  a\n\nb  \n\n c \n"
        assertEquals(listOf("  a", "b", " c"), LocalLlmAgentSession.progressNonBlankLines(multi))
        assertEquals(3, LocalLlmAgentSession.progressNonBlankLineCount(multi))
        assertEquals("a", LocalLlmAgentSession.progressFirstNonBlankLine(multi))
        assertEquals("", LocalLlmAgentSession.progressFirstNonBlankLine("  \n\n"))

        assertEquals("hello", LocalLlmAgentSession.progressDetailSnippet("hello"))
        assertEquals(
            "x".repeat(LocalLlmAgentSession.PROGRESS_SNIPPET_MAX_CHARS - 1) + "…",
            LocalLlmAgentSession.progressDetailSnippet("x".repeat(LocalLlmAgentSession.PROGRESS_SNIPPET_MAX_CHARS + 20))
        )

        assertEquals("42 chars", LocalLlmAgentSession.progressFormatCharCount(42))
        assertEquals("1k chars", LocalLlmAgentSession.progressFormatCharCount(1000))
        assertEquals("1.2k chars", LocalLlmAgentSession.progressFormatCharCount(1200))
        assertEquals("13k chars", LocalLlmAgentSession.progressFormatCharCount(12_500))

        val preview = LocalLlmAgentSession.progressFormatPreview("one\ntwo\nthree\nfour")
        assertEquals("one\ntwo\nthree", preview)

        val direct = LocalLlmAgentSession.progressFormatMultiLineSummary(
            kind = "tool_result",
            tool = "list_directory_tree",
            detail = directoryTreeDetail()
        )
        assertTrue(direct.contains("lines") && direct.contains("chars"), direct)
        assertTrue(direct.startsWith("  tool_result list_directory_tree"), direct)
    }

    // --- onCommandOutput / collapsible tool_result emit (wo-03-01) ---

    @Test
    fun `shouldEmitCommandOutput only for large tool_result`() {
        val tree = directoryTreeDetail()
        assertTrue(LocalLlmAgentSession.isLargeToolResultDetail(tree))
        assertTrue(
            LocalLlmAgentSession.shouldEmitCommandOutput(
                AgentClient.AgentEvent(kind = "tool_result", tool = "list_directory_tree", detail = tree)
            )
        )
        // Case-insensitive kind.
        assertTrue(
            LocalLlmAgentSession.shouldEmitCommandOutput(
                AgentClient.AgentEvent(kind = "TOOL_RESULT", tool = "list_directory_tree", detail = tree)
            )
        )
        // Short single-line tool_result: no collapsible.
        assertFalse(LocalLlmAgentSession.isLargeToolResultDetail("ok: 3 files"))
        assertFalse(
            LocalLlmAgentSession.shouldEmitCommandOutput(
                AgentClient.AgentEvent(kind = "tool_result", tool = "list_dir", detail = "ok: 3 files")
            )
        )
        // Non-tool_result: never, even with large body.
        assertFalse(
            LocalLlmAgentSession.shouldEmitCommandOutput(
                AgentClient.AgentEvent(kind = "tool_call", tool = "list_directory_tree", detail = tree)
            )
        )
        assertFalse(
            LocalLlmAgentSession.shouldEmitCommandOutput(
                AgentClient.AgentEvent(kind = "status", detail = tree)
            )
        )
        // Char-length alone triggers (long single line).
        val longLine = "x".repeat(LocalLlmAgentSession.COMMAND_OUTPUT_MIN_CHARS + 1)
        assertTrue(LocalLlmAgentSession.isLargeToolResultDetail(longLine))
        assertTrue(
            LocalLlmAgentSession.shouldEmitCommandOutput(
                AgentClient.AgentEvent(kind = "tool_result", detail = longLine)
            )
        )
        // Line-count alone (3 non-blank short lines).
        val threeLines = "a\nb\nc"
        assertTrue(LocalLlmAgentSession.progressNonBlankLineCount(threeLines) > 2)
        assertTrue(LocalLlmAgentSession.isLargeToolResultDetail(threeLines))
    }

    @Test
    fun `commandOutputLabel prefers tool name`() {
        assertEquals(
            "list_directory_tree",
            LocalLlmAgentSession.commandOutputLabel(
                AgentClient.AgentEvent(kind = "tool_result", tool = "list_directory_tree", detail = "x")
            )
        )
        assertEquals(
            "tool_result task-1",
            LocalLlmAgentSession.commandOutputLabel(
                AgentClient.AgentEvent(kind = "tool_result", taskId = "task-1", detail = "x")
            )
        )
        assertEquals(
            "tool_result",
            LocalLlmAgentSession.commandOutputLabel(
                AgentClient.AgentEvent(kind = "tool_result", detail = "x")
            )
        )
    }

    @Test
    fun `poll loop large multi-line tool_result emits full onCommandOutput and short onLogLine`() {
        val tree = directoryTreeDetail()
        val largeEvent = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "list_directory_tree",
            detail = tree
        )
        val started = run(id = "run-cmd-out", state = "executing", dryRun = true, maxSteps = 5)
        val withEvent = started.copy(
            state = "executing",
            step = 1,
            events = listOf(largeEvent)
        )
        val done = started.copy(
            state = "done",
            step = 1,
            events = listOf(largeEvent),
            finalAnswer = "listed tree"
        )
        val fake = FakeAgentOps(
            startSnapshot = started,
            pollSnapshots = listOf(withEvent, done)
        )
        val commandOutputs = mutableListOf<Pair<String, String>>()
        val logLines = mutableListOf<String>()
        val activityLines = mutableListOf<String>()
        val sessionLog = com.waryway.gab.diagnostics.SessionLog(onLine = { activityLines.add(it) })
        val session = LocalLlmAgentSession(
            ops = fake,
            sessionLog = sessionLog,
            pollIntervalMs = 10L,
            timeoutMs = 2_000L,
            onLogLine = { logLines += it },
            onCommandOutput = { label, output -> commandOutputs += label to output }
        )

        val result = session.run(goal = "list dirs", dryRun = true)
        assertTrue(result.finalContent.contains("listed tree"), result.finalContent)

        assertEquals(1, commandOutputs.size, "expected one collapsible emit; got $commandOutputs")
        assertEquals("list_directory_tree", commandOutputs[0].first)
        // Full detail body — not summary-only.
        assertEquals(tree, commandOutputs[0].second)
        assertTrue(commandOutputs[0].second.contains("AGENTS.md"), commandOutputs[0].second)
        assertTrue(commandOutputs[0].second.contains("\n"), "full body should be multi-line")

        // onLogLine gets short formatEventSummary shape (stats, not space-joined tree).
        val summaryLines = logLines.filter { it.contains("tool_result") || it.contains("list_directory_tree") }
        assertTrue(summaryLines.isNotEmpty(), "expected short log line; logs=$logLines")
        val summary = summaryLines.first()
        assertTrue(summary.contains("lines") && summary.contains("chars"), "summary=$summary")
        assertTrue(
            summary.length <= LocalLlmAgentSession.PROGRESS_SUMMARY_MAX_CHARS + 20,
            "log line should stay short: $summary"
        )
        val spaceJoined = tree.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        assertFalse(summary.contains(spaceJoined), "must not embed full space-joined tree in log")

        // Session-side Activity log has full (or soft-capped) body when collapsible emitted.
        assertTrue(
            activityLines.any { it.contains("result") && it.contains("list_directory_tree") && it.contains("AGENTS.md") },
            "activity log should hold full tool body; lines=$activityLines"
        )
        // Quiet poll regression: all GETs still quiet.
        assertTrue(fake.getQuietFlags.all { it }, "quiet flags=${fake.getQuietFlags}")
    }

    @Test
    fun `poll loop short tool_result does not emit onCommandOutput`() {
        val shortEvent = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "list_dir",
            detail = "ok: 3 files"
        )
        val started = run(id = "run-cmd-short", state = "executing", dryRun = true)
        val withEvent = started.copy(events = listOf(shortEvent), step = 1)
        val done = started.copy(
            state = "done",
            step = 1,
            events = listOf(shortEvent),
            finalAnswer = "done short"
        )
        val fake = FakeAgentOps(startSnapshot = started, pollSnapshots = listOf(withEvent, done))
        val commandOutputs = mutableListOf<Pair<String, String>>()
        val logLines = mutableListOf<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 10L,
            timeoutMs = 2_000L,
            onLogLine = { logLines += it },
            onCommandOutput = { l, o -> commandOutputs += l to o }
        )
        session.run(goal = "short result", dryRun = true)
        assertTrue(commandOutputs.isEmpty(), "short tool_result must not open collapsible: $commandOutputs")
        assertTrue(
            logLines.any { it.contains("tool_result") || it.contains("list_dir") || it.contains("ok: 3") },
            "expected status log line; logs=$logLines"
        )
    }

    @Test
    fun `poll loop non-tool_result events do not emit onCommandOutput`() {
        val callEvent = AgentClient.AgentEvent(
            kind = "tool_call",
            tool = "list_directory_tree",
            detail = "path=."
        )
        val statusEvent = AgentClient.AgentEvent(
            kind = "status",
            detail = directoryTreeDetail() // large body, but not tool_result
        )
        val started = run(id = "run-cmd-nont", state = "executing", dryRun = true)
        val withEvents = started.copy(events = listOf(callEvent, statusEvent), step = 1)
        val done = started.copy(
            state = "done",
            step = 1,
            events = listOf(callEvent, statusEvent),
            finalAnswer = "done nont"
        )
        val fake = FakeAgentOps(startSnapshot = started, pollSnapshots = listOf(withEvents, done))
        val commandOutputs = mutableListOf<Pair<String, String>>()
        val logLines = mutableListOf<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 10L,
            timeoutMs = 2_000L,
            onLogLine = { logLines += it },
            onCommandOutput = { l, o -> commandOutputs += l to o }
        )
        session.run(goal = "no collapsible", dryRun = true)
        assertTrue(commandOutputs.isEmpty(), "non-tool_result must not emit: $commandOutputs")
        assertTrue(logLines.any { it.contains("tool_call") }, "tool_call should still log; logs=$logLines")
    }

    // --- formatFinalContent / formatTerminalStatus / formatStatusLine (wo-03-01 + wo-02-01) ---

    @Test
    fun `formatFinalContent done with finalAnswer dry-run preserves answer and badge`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = true, finalAnswer = "Plan: edit main.go", repoRoot = "/repo")
        )
        // Answer first, then Thinking (goal/plan), then footer.
        assertTrue(content.startsWith("### Answer"), content)
        assertTrue(content.contains("### Thinking"), content)
        assertTrue(content.contains("### Agent result — done · DRY-RUN"), content)
        assertTrue(content.contains("Plan: edit main.go"), content)
        assertTrue(content.contains(LocalLlmAgentSession.DRY_RUN_BADGE.trim()))
        assertTrue(content.contains("repoRoot: /repo"))
        assertTrue(content.contains("— tools:"))
        assertFalse(content.contains("Apply mode"))
    }

    @Test
    fun `formatFinalContent done with finalAnswer apply mode badge`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = false, finalAnswer = "  Applied fix  ")
        )
        assertTrue(content.startsWith("### Answer"), content)
        assertTrue(content.contains("### Thinking"), content)
        assertTrue(content.contains("### Agent result — done · APPLY"), content)
        assertTrue(content.contains("Applied fix"), content)
        assertTrue(content.contains(LocalLlmAgentSession.APPLY_BADGE.trim()))
        assertFalse(content.contains("Dry-run"))
    }

    @Test
    fun `formatFinalContent answer first extracts Answer section without goal noise`() {
        val raw = """
            Goal: find quanta realms
            Plan: Heuristic: find/print product README

            Answer:
            apps/quanta-realms/README.md
            # Quanta Realms

            **Waryway** product: persistent space-empire game.

            ---
            Goal: find quanta realms
            Plan: Heuristic: find/print product README
            (via read_file)
        """.trimIndent()
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = false, finalAnswer = raw, goal = "find quanta realms")
        )
        assertTrue(content.startsWith("### Answer"), content)
        val answerIdx = content.indexOf("### Answer")
        val thinkingIdx = content.indexOf("### Thinking")
        assertTrue(answerIdx >= 0 && thinkingIdx > answerIdx, content)
        val answerBlock = content.substring(answerIdx, thinkingIdx)
        assertTrue(answerBlock.contains("Quanta Realms"), answerBlock)
        assertTrue(answerBlock.contains("space-empire"), answerBlock)
        // Goal/Plan noise must not lead the Answer body (lives under Thinking).
        assertFalse(answerBlock.contains("**Goal:**"), answerBlock)
        assertFalse(answerBlock.contains("### [done]"), answerBlock)
        assertTrue(content.contains("### Agent result"), content)
        assertTrue(content.contains("**Goal:** find quanta realms"), content)
    }

    @Test
    fun `formatFinalDisplayParts splits answer from thinking for UI`() {
        val parts = LocalLlmAgentSession.formatFinalDisplayParts(
            run(
                state = "done",
                dryRun = false,
                goal = "what project is this?",
                finalAnswer = "Answer:\nThis is the Waryway stack monorepo.\n",
                plan = AgentClient.AgentPlan(
                    summary = "Read root README",
                    tasks = listOf(
                        AgentClient.AgentTask(
                            id = "t1",
                            title = "Read README",
                            tool = "read_file",
                            status = "done",
                            result = "# Retronium\nA retro app."
                        )
                    )
                )
            )
        )
        assertNotNull(parts.answer)
        assertNotNull(parts.thinking)
        assertTrue(parts.answer!!.contains("Waryway stack monorepo"), parts.answer)
        assertFalse(parts.answer!!.contains("**Goal:**"), parts.answer)
        assertTrue(parts.thinking!!.contains("**Goal:** what project is this?"), parts.thinking)
        assertTrue(parts.thinking!!.contains("### Agent result — done · APPLY"), parts.thinking)
        assertTrue(parts.fullText.startsWith("### Answer"), parts.fullText)
        assertTrue(parts.fullText.contains("### Thinking"), parts.fullText)
    }

    @Test
    fun `condenseAnswerForDisplay excerpts multi-file dumps`() {
        val dump = buildString {
            appendLine("AGENTS.md")
            appendLine("# AGENTS.md")
            appendLine("## Repository map")
            repeat(40) { i ->
                appendLine("- `apps/mod$i/` — long description of module $i with lots of detail padding.")
            }
            appendLine("README.md")
            appendLine("# Retronium")
            appendLine()
            appendLine("A client-side only web application for conducting scrum retrospectives.")
            repeat(30) { i ->
                appendLine("Feature line $i about peer-to-peer and phases of the retro process.")
            }
        }
        assertTrue(dump.length > LocalLlmAgentSession.FINAL_ANSWER_SOFT_CAP)
        val condensed = LocalLlmAgentSession.condenseAnswerForDisplay(dump)
        assertTrue(condensed.contains("**README.md**") || condensed.contains("README.md"), condensed)
        assertTrue(condensed.contains("**AGENTS.md**") || condensed.contains("AGENTS.md"), condensed)
        assertTrue(condensed.contains("Retronium") || condensed.contains("Repository map"), condensed)
        assertTrue(
            condensed.length < dump.length,
            "condensed should be shorter than raw dump: ${condensed.length} vs ${dump.length}"
        )
        assertTrue(
            condensed.length <= LocalLlmAgentSession.FINAL_ANSWER_SOFT_CAP + 200,
            "condensed should stay near soft-cap: ${condensed.length}"
        )
        // Full dump tail should not dominate.
        assertFalse(condensed.contains("Feature line 29"), condensed)
    }

    @Test
    fun `extractPrimaryAnswer strips numbered line prefixes and meta`() {
        val raw = """
            Answer:
            apps/quanta-realms/README.md
            1|# Quanta Realms
            2|
            3|**Waryway** product

            ---
            Goal: find quanta
            (via read_file)
        """.trimIndent()
        val got = LocalLlmAgentSession.extractPrimaryAnswer(raw)
        assertTrue(got.contains("# Quanta Realms"), got)
        assertTrue(got.contains("**Waryway**"), got)
        assertFalse(got.contains("1|#"), got)
        assertFalse(got.contains("Goal: find"), got)
        assertFalse(got.contains("via read_file"), got)
    }

    @Test
    fun `formatFinalContent done empty finalAnswer is not blank success bubble`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = true, finalAnswer = null)
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("no final answer"), content)
        assertTrue(content.contains("state=done"), content)
        assertTrue(content.startsWith("### Answer") || content.contains("### Agent result"), content)
        assertFalse(content.startsWith("Error:"))
    }

    @Test
    fun `formatFinalContent done blank finalAnswer is not blank`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = false, finalAnswer = "   ")
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("no final answer"), content)
    }

    @Test
    fun `formatFinalContent failed with error shows Error prefix`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "failed", dryRun = true, error = "tool boom", finalAnswer = null)
        )
        assertEquals("Error: tool boom", content)
    }

    @Test
    fun `formatFinalContent failed with error and partial answer includes both`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "failed", error = "step limit", finalAnswer = "Partial notes")
        )
        assertTrue(content.startsWith("Error: step limit"))
        assertTrue(content.contains("Partial notes"))
    }

    @Test
    fun `formatFinalContent cancelled is Stopped by user`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "cancelled", finalAnswer = null)
        )
        assertEquals(LocalLlmAgentSession.STOPPED_BY_USER, content)
    }

    @Test
    fun `formatFinalContent userStopped without answer is Stopped by user`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "running", finalAnswer = null),
            userStopped = true
        )
        assertEquals(LocalLlmAgentSession.STOPPED_BY_USER, content)
    }

    @Test
    fun `formatFinalContent userStopped keeps non-blank finalAnswer when present`() {
        // If server already produced answer before stop, surface it with hierarchy + badge.
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "cancelled", dryRun = true, finalAnswer = "Halfway there"),
            userStopped = true
        )
        assertTrue(content.contains("Halfway there"), content)
        assertTrue(content.contains("Dry-run"), content)
        assertTrue(content.contains("### Answer"), content)
        assertFalse(content == LocalLlmAgentSession.STOPPED_BY_USER)
    }

    @Test
    fun `formatFinalContent failed empty error still non-blank`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "failed", error = null, finalAnswer = null)
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("no final answer") || content.contains("failed"), content)
    }

    @Test
    fun `formatFinalContent odd blank state still non-blank`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "", error = null, finalAnswer = "  ")
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("unknown") || content.contains("no final answer"), content)
    }

    @Test
    fun `formatFinalContent non-failed with error still shows Error`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", error = "soft warning", finalAnswer = null)
        )
        // empty answer + non-blank error → Error: path (not blank bubble)
        assertEquals("Error: soft warning", content)
    }

    @Test
    fun `formatFinalContent describe-repo fixture has hierarchy caps and tools footer`() {
        val treeDump = buildString {
            appendLine(".")
            appendLine("├── src/")
            appendLine("│   ├── main/")
            repeat(200) { i ->
                appendLine("│   │   ├── File$i.kt  // padding line with path details")
            }
            appendLine("└── README.md")
        }
        assertTrue(treeDump.length > LocalLlmAgentSession.FINAL_TASK_EVIDENCE_CAP)

        val searchHits = buildString {
            repeat(80) { i ->
                appendLine("hit $i: com/waryway/gab/chat/LocalLlmAgentSession.kt: keyword match context")
            }
        }
        assertTrue(searchHits.length > LocalLlmAgentSession.FINAL_TASK_EVIDENCE_CAP)

        val hugeAnswer = buildString {
            appendLine("This repo is a JetBrains plugin for Local LLM agent runs.")
            appendLine()
            appendLine("Directory tree:")
            append(treeDump)
            appendLine()
            appendLine("Search hits:")
            append(searchHits)
            appendLine()
            appendLine("Conclusion: multi-module Kotlin IntelliJ plugin.")
        }
        assertTrue(hugeAnswer.length > LocalLlmAgentSession.FINAL_ANSWER_SOFT_CAP)

        val plan = AgentClient.AgentPlan(
            summary = "Explore repo layout then keyword-search agent code",
            tasks = listOf(
                AgentClient.AgentTask(
                    id = "t1",
                    title = "List repo root",
                    tool = "list_directory_tree",
                    status = "done",
                    result = treeDump
                ),
                AgentClient.AgentTask(
                    id = "t2",
                    title = "Keyword search",
                    tool = "search_code",
                    status = "done",
                    result = searchHits
                )
            )
        )
        val events = listOf(
            AgentClient.AgentEvent(kind = "tool_call", tool = "list_directory_tree", detail = "."),
            AgentClient.AgentEvent(kind = "tool_result", tool = "list_directory_tree", detail = "ok"),
            AgentClient.AgentEvent(kind = "tool_call", tool = "search_code", detail = "LocalLlm"),
            AgentClient.AgentEvent(kind = "tool_result", tool = "search_code", detail = "ok"),
            AgentClient.AgentEvent(kind = "tool_call", tool = "read_file", detail = "README.md"),
            AgentClient.AgentEvent(kind = "tool_result", tool = "read_file", detail = "ok")
        )
        val content = LocalLlmAgentSession.formatFinalContent(
            run(
                state = "done",
                dryRun = false,
                goal = "describe this repo",
                plan = plan,
                finalAnswer = hugeAnswer,
                repoRoot = "C:/dev/waryway-gab-plugin",
                events = events
            )
        )

        // Structure: Answer → Thinking (header/goal/plan) → Details → footer
        assertTrue(content.startsWith("### Answer"), content)
        assertTrue(content.contains("### Thinking"), content)
        assertTrue(content.contains("### Agent result — done · APPLY"), content)
        assertTrue(content.contains("**Goal:** describe this repo"), content)
        assertTrue(content.contains("**Plan:** Explore repo layout"), content)
        assertTrue(content.contains("- [done] List repo root [list_directory_tree]"), content)
        assertTrue(content.contains("- [done] Keyword search [search_code]"), content)
        assertTrue(content.contains("### Details") || content.contains("### Task evidence"), content)
        assertTrue(content.contains("#### [done] List repo root"), content)
        assertTrue(content.contains("#### [done] Keyword search"), content)

        // Soft-caps: large bodies truncated with explicit marker; full tree does not dominate
        assertTrue(content.contains("truncated for UI"), content)
        assertTrue(content.contains("full text in Activity log"), content)
        assertTrue(
            content.length < hugeAnswer.length + treeDump.length,
            "final content should be capped well below raw answer+tree dump size: ${content.length}"
        )
        // Multi-KB tree must not appear in full under evidence
        assertFalse(
            content.contains(treeDump.takeLast(80)),
            "uncapped tail of directory tree should not appear"
        )

        // Footer: APPLY badge, repoRoot, tools count ≥ tool_call events
        assertTrue(content.contains(LocalLlmAgentSession.APPLY_BADGE.trim()), content)
        assertTrue(content.contains("repoRoot: C:/dev/waryway-gab-plugin"), content)
        assertTrue(content.contains("— tools: 3"), content)
        assertEquals(3, LocalLlmAgentSession.countFinalToolCalls(
            run(events = events, dryRun = false, finalAnswer = "x")
        ))
    }

    /**
     * Section-04 acceptance map (pure): one synthetic "describe this repo" shape across
     * progress summaries, collapsible emit gate, and hierarchical final content.
     * Reuses [directoryTreeDetail] / [searchHitsDetail]; pads only for final evidence caps.
     */
    @Test
    fun `describe-repo acceptance shape progress emit and final hierarchy`() {
        val tree = directoryTreeDetail()
        val hits = searchHitsDetail()
        assertTrue(tree.lines().count { it.isNotBlank() } > 1)
        assertTrue(LocalLlmAgentSession.progressNonBlankLineCount(hits) > 1)

        // 1) Multi-line directory tree → progress is summary, not space-joined dump
        val treeEv = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "list_directory_tree",
            detail = tree
        )
        val treeSummary = LocalLlmAgentSession.formatEventSummary(treeEv)
        assertTrue(treeSummary.contains("tool_result") && treeSummary.contains("list_directory_tree"), treeSummary)
        assertTrue(treeSummary.contains("lines") && treeSummary.contains("chars"), treeSummary)
        assertTrue(treeSummary.length <= LocalLlmAgentSession.PROGRESS_SUMMARY_MAX_CHARS, treeSummary)
        val treeJoined = tree.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        assertFalse(treeSummary.contains(treeJoined), "progress must not embed full space-joined tree")

        // 2) Large search-hit detail → capped progress summary
        val searchEv = AgentClient.AgentEvent(
            kind = "tool_result",
            tool = "search_code",
            detail = hits
        )
        val searchSummary = LocalLlmAgentSession.formatEventSummary(searchEv)
        assertTrue(searchSummary.contains("tool_result") && searchSummary.contains("search_code"), searchSummary)
        assertTrue(searchSummary.contains("lines") && searchSummary.contains("chars"), searchSummary)
        assertTrue(searchSummary.length <= LocalLlmAgentSession.PROGRESS_SUMMARY_MAX_CHARS, searchSummary)
        val hitsJoined = hits.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        assertFalse(searchSummary.contains(hitsJoined), "progress must not embed full space-joined search dump")

        // 3) Large tool_result → collapsible gate; emit path keeps full detail body (not summary)
        assertTrue(LocalLlmAgentSession.shouldEmitCommandOutput(treeEv))
        assertTrue(LocalLlmAgentSession.shouldEmitCommandOutput(searchEv))
        assertEquals("list_directory_tree", LocalLlmAgentSession.commandOutputLabel(treeEv))
        assertEquals("search_code", LocalLlmAgentSession.commandOutputLabel(searchEv))
        assertEquals(tree, treeEv.detail)
        assertEquals(hits, searchEv.detail)
        assertTrue(treeEv.detail.contains("\n") && treeEv.detail.contains("AGENTS.md"))

        // 4) Final APPLY run: plan + tasks + large answer → hierarchy, caps, tools footer
        // Unique pad tails live only in task evidence (soft-capped); answer is large enough to soft-cap.
        val evidenceTailMarker = "UNIQUE_EVIDENCE_TREE_TAIL_MARKER_ZZ9"
        val hitsTailMarker = "UNIQUE_EVIDENCE_HITS_TAIL_MARKER_YY8"
        val evidenceTree = buildString {
            appendLine(tree)
            repeat(40) { i -> appendLine("evidence-pad/tree/$i/File$i.kt") }
            appendLine(evidenceTailMarker)
        }
        val evidenceHits = buildString {
            appendLine(hits)
            repeat(40) { i -> appendLine("hit-pad $i: path/File$i.kt: match context") }
            appendLine(hitsTailMarker)
        }
        assertTrue(evidenceTree.length > LocalLlmAgentSession.FINAL_TASK_EVIDENCE_CAP)
        assertTrue(evidenceHits.length > LocalLlmAgentSession.FINAL_TASK_EVIDENCE_CAP)
        val finalAnswer = buildString {
            appendLine("Overview: JetBrains plugin wrapping a local LLM agent.")
            appendLine("Directory tree (verbose):")
            // Pad answer past soft-cap without reusing evidence tails (those assert evidence-only caps).
            repeat(LocalLlmAgentSession.FINAL_ANSWER_SOFT_CAP / 40 + 5) { i ->
                appendLine("answer-pad line $i with filler about modules and packages.")
            }
            appendLine("Conclusion: multi-module Kotlin IntelliJ plugin.")
        }
        assertTrue(finalAnswer.length > LocalLlmAgentSession.FINAL_ANSWER_SOFT_CAP)
        val plan = AgentClient.AgentPlan(
            summary = "List tree then search agent code",
            tasks = listOf(
                AgentClient.AgentTask(
                    id = "t1",
                    title = "List repo root",
                    tool = "list_directory_tree",
                    status = "done",
                    result = evidenceTree
                ),
                AgentClient.AgentTask(
                    id = "t2",
                    title = "Keyword search",
                    tool = "search_code",
                    status = "done",
                    result = evidenceHits
                )
            )
        )
        val events = listOf(
            AgentClient.AgentEvent(kind = "tool_call", tool = "list_directory_tree", detail = "."),
            AgentClient.AgentEvent(kind = "tool_result", tool = "list_directory_tree", detail = tree),
            AgentClient.AgentEvent(kind = "tool_call", tool = "search_code", detail = "LocalLlm"),
            AgentClient.AgentEvent(kind = "tool_result", tool = "search_code", detail = hits)
        )
        val content = LocalLlmAgentSession.formatFinalContent(
            run(
                state = "done",
                dryRun = false,
                goal = "describe this repo",
                plan = plan,
                finalAnswer = finalAnswer,
                repoRoot = "C:/dev/waryway-gab-plugin",
                events = events
            )
        )
        assertTrue(content.startsWith("### Answer"), content)
        assertTrue(content.contains("### Thinking"), content)
        assertTrue(content.contains("### Agent result — done · APPLY"), content)
        assertTrue(content.contains("**Goal:** describe this repo"), content)
        assertTrue(content.contains("Overview: JetBrains plugin"), content)
        assertTrue(content.contains("### Details") || content.contains("### Task evidence"), content)
        assertTrue(content.contains("truncated for UI"), content)
        assertTrue(content.contains("full text in Activity log"), content)
        // Tool dumps do not dominate: unique evidence tails and answer tail are soft-capped out.
        assertFalse(content.contains(evidenceTailMarker), "evidence tree tail must be capped out")
        assertFalse(content.contains(hitsTailMarker), "search evidence tail must be capped out")
        assertFalse(content.contains("Conclusion: multi-module"), "answer soft-cap should drop distant conclusion")
        assertTrue(
            content.length < finalAnswer.length + evidenceTree.length,
            "final content should be well below raw answer+tree size: ${content.length}"
        )
        assertTrue(content.contains(LocalLlmAgentSession.APPLY_BADGE.trim()), content)
        assertTrue(content.contains("repoRoot: C:/dev/waryway-gab-plugin"), content)
        assertTrue(content.contains("— tools: 2"), content)
    }

    @Test
    fun `softCapFinalText leaves short text unchanged and marks long text`() {
        assertEquals("short", LocalLlmAgentSession.softCapFinalText("short", 100))
        val long = "a".repeat(50)
        val capped = LocalLlmAgentSession.softCapFinalText(long, 20)
        assertTrue(capped.startsWith("a".repeat(20)))
        assertTrue(capped.contains("truncated for UI — 30 more chars"), capped)
    }

    @Test
    fun `formatTerminalStatus done dry-run vs apply`() {
        assertEquals(
            "▸ Agent done (dry-run)",
            LocalLlmAgentSession.formatTerminalStatus(run(state = "done", dryRun = true))
        )
        assertEquals(
            "▸ Agent done (applied)",
            LocalLlmAgentSession.formatTerminalStatus(run(state = "done", dryRun = false))
        )
    }

    @Test
    fun `formatTerminalStatus failed and cancelled`() {
        assertEquals(
            "▸ Agent failed",
            LocalLlmAgentSession.formatTerminalStatus(run(state = "failed"))
        )
        assertEquals(
            "▸ Agent cancelled",
            LocalLlmAgentSession.formatTerminalStatus(run(state = "cancelled"))
        )
    }

    @Test
    fun `formatTerminalStatus userStopped maps to cancelled when not done or failed`() {
        assertEquals(
            "▸ Agent cancelled",
            LocalLlmAgentSession.formatTerminalStatus(
                run(state = "running"),
                userStopped = true
            )
        )
        // userStopped on failed should stay failed
        assertEquals(
            "▸ Agent failed",
            LocalLlmAgentSession.formatTerminalStatus(
                run(state = "failed"),
                userStopped = true
            )
        )
    }

    @Test
    fun `formatStatusLine reflects dry-run vs APPLY and steps`() {
        assertEquals(
            "Agent [dry-run] planning",
            LocalLlmAgentSession.formatStatusLine(run(state = "planning", dryRun = true))
        )
        assertEquals(
            "Agent [APPLY] running 2/8",
            LocalLlmAgentSession.formatStatusLine(
                run(state = "running", dryRun = false, step = 2, maxSteps = 8)
            )
        )
        // maxSteps 0 → no step suffix
        assertEquals(
            "Agent [dry-run] done",
            LocalLlmAgentSession.formatStatusLine(run(state = "done", dryRun = true, maxSteps = 0))
        )
    }

    @Test
    fun `formatStatusLine appends server message when present`() {
        assertEquals(
            "Agent [APPLY] planning 0/30 — generating plan…",
            LocalLlmAgentSession.formatStatusLine(
                run(
                    state = "planning",
                    dryRun = false,
                    step = 0,
                    maxSteps = 30,
                    message = "generating plan…"
                )
            )
        )
        // Blank message must not add " — "
        assertEquals(
            "Agent [dry-run] planning",
            LocalLlmAgentSession.formatStatusLine(
                run(state = "planning", dryRun = true, message = "  ")
            )
        )
    }

    // --- quiet poll / progress-only / heartbeat (fail package: poll spam) ---

    @Test
    fun `poll loop calls getRun with quiet true and finishes on progress`() {
        val started = run(id = "run-quiet", state = "planning", dryRun = false, maxSteps = 30)
        val done = run(
            id = "run-quiet",
            state = "done",
            dryRun = false,
            maxSteps = 30,
            step = 1,
            finalAnswer = "ok"
        )
        val fake = FakeAgentOps(
            startSnapshot = started,
            pollSnapshots = listOf(
                started.copy(state = "planning", step = 0, message = "thinking"),
                started.copy(state = "executing", step = 1, message = "tool"),
                done
            )
        )
        val statuses = CopyOnWriteArrayList<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 10L,
            timeoutMs = 2_000L,
            onStatus = { statuses.add(it) }
        )

        val result = session.run(goal = "do work", dryRun = false)
        assertTrue(result.finalContent.contains("### Answer"), result.finalContent)
        assertTrue(result.finalContent.contains("ok"), result.finalContent)
        assertTrue(result.finalContent.contains(LocalLlmAgentSession.APPLY_BADGE.trim()), result.finalContent)
        assertTrue(fake.getIds.isNotEmpty(), "expected at least one poll")
        assertTrue(
            fake.getQuietFlags.all { it },
            "every getRun must be quiet=true to avoid activity-log flood; flags=${fake.getQuietFlags}"
        )
        assertTrue(
            statuses.any { it.contains("planning") || it.contains("executing") || it.contains("done") },
            statuses.toString()
        )
    }

    @Test
    fun `stuck planning does not spam onStatus for identical quiet polls`() {
        val started = run(id = "run-hb", state = "planning", dryRun = true, maxSteps = 30)
        // Repeat identical planning snaps so FakeAgentOps does not flip to "running" when exhausted.
        val stuck = List(40) { started.copy(state = "planning", step = 0, message = null) }
        val fake = FakeAgentOps(startSnapshot = started, pollSnapshots = stuck)
        val statuses = CopyOnWriteArrayList<String>()
        val cancelled = AtomicBoolean(false)
        val session = LocalLlmAgentSession(
            ops = fake,
            cancelled = cancelled,
            pollIntervalMs = 8L,
            timeoutMs = 5_000L,
            onStatus = { statuses.add(it) }
        )

        // Production heartbeat is 15s — too slow for unit tests. Assert that identical-state
        // polls do not flood statuses (fail package had hundreds of SYS agent get lines).
        val worker = Thread {
            try {
                session.run(goal = "slow plan", dryRun = true)
            } catch (_: Throwable) {
            }
        }
        worker.start()
        val deadline = System.currentTimeMillis() + 1_500L
        while (fake.getIds.size < 8 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        cancelled.set(true)
        session.cancelActiveRun()
        worker.join(2_000)

        assertTrue(fake.getIds.size >= 5, "expected multiple quiet polls; got ${fake.getIds.size}")
        assertTrue(fake.getQuietFlags.all { it }, "quiet flags=${fake.getQuietFlags}")
        // Status updates only on state/step/message change — identical planning snaps → few statuses.
        val planningStatuses = statuses.filter { it.contains("planning", ignoreCase = true) }
        assertTrue(
            planningStatuses.size <= 3,
            "identical planning state must not spam onStatus; statuses=$statuses"
        )
    }

    @Test
    fun `default timeout is 30 minutes not legacy 8 minutes`() {
        assertEquals(30L * 60L * 1000L, LocalLlmAgentSession.DEFAULT_TIMEOUT_MS)
        assertTrue(LocalLlmAgentSession.DEFAULT_TIMEOUT_MS > 480_000L)
        assertEquals(1_500L, LocalLlmAgentSession.DEFAULT_POLL_INTERVAL_MS)
        assertEquals(5_000L, LocalLlmAgentSession.MAX_POLL_INTERVAL_MS)
        assertEquals(15_000L, LocalLlmAgentSession.HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun `resolveStallTimeoutMs floors and never exceeds absolute`() {
        val absolute = 30L * 60L * 1000L
        val stall = LocalLlmAgentSession.resolveStallTimeoutMs(absolute)
        assertTrue(stall >= LocalLlmAgentSession.MIN_STALL_TIMEOUT_MS, "stall=$stall")
        assertTrue(stall <= absolute, "stall=$stall absolute=$absolute")
        // Explicit stall is clamped to absolute.
        assertEquals(5_000L, LocalLlmAgentSession.resolveStallTimeoutMs(5_000L, stallTimeoutMs = 60_000L))
        assertEquals(3_000L, LocalLlmAgentSession.resolveStallTimeoutMs(10_000L, stallTimeoutMs = 3_000L))
    }

    @Test
    fun `progress resets stall clock so absolute budget can be used`() {
        val started = run(id = "run-progress-1", state = "planning", dryRun = true)
        // First polls stay planning; then step advances before stall would fire.
        val pollSnapshots = listOf(
            started.copy(state = "planning", step = 0, message = "thinking"),
            started.copy(state = "planning", step = 0, message = "thinking"),
            started.copy(state = "executing", step = 1, message = "tool"),
            started.copy(state = "done", step = 1, finalAnswer = "ok via progress")
        )
        val fake = FakeAgentOps(startSnapshot = started, pollSnapshots = pollSnapshots)
        // Stall budget long enough that a few polls can progress before it fires;
        // without progress resets, a tight stall would abort during planning.
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 10L,
            timeoutMs = 5_000L,
            stallTimeoutMs = 500L
        )
        val result = session.run(goal = "progress test", dryRun = true)
        assertTrue(result.finalContent.contains("ok via progress"), result.finalContent)
        assertTrue(result.finalContent.contains("### Answer"), result.finalContent)
        assertNull(session.currentRunId())
        // Progress must have reset the stall clock (multiple quiet polls + state change).
        assertTrue(fake.getIds.size >= 3, "expected progress polls; got ${fake.getIds.size}")
    }

    @Test
    fun `stall timeout fires when no progress within stall budget`() {
        val started = run(id = "run-stall-1", state = "planning", dryRun = true)
        val stuck = List(20) { started.copy(state = "planning", step = 0) }
        val fake = FakeAgentOps(startSnapshot = started, pollSnapshots = stuck)
        val session = LocalLlmAgentSession(
            ops = fake,
            pollIntervalMs = 10L,
            timeoutMs = 5_000L,
            stallTimeoutMs = 40L
        )
        val ex = assertFailsWith<LocalLlmAgentSession.AgentTimeoutException> {
            session.run(goal = "stall me", dryRun = true)
        }
        assertTrue(ex.message!!.contains("timed out"), ex.message)
        assertTrue(
            ex.reason.contains("stall") || ex.message!!.contains("stall"),
            "reason=${ex.reason} msg=${ex.message}"
        )
        assertTrue(fake.cancelIds.contains("run-stall-1"))
        assertNull(session.currentRunId())
    }

    @Test
    fun `soft budget warnings fire before absolute timeout`() {
        val started = run(id = "run-soft-1", state = "planning", dryRun = true)
        val stuck = List(80) { started.copy(state = "planning", step = 0) }
        val fake = FakeAgentOps(startSnapshot = started, pollSnapshots = stuck)
        val logs = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val session = LocalLlmAgentSession(
            ops = fake,
            onStatus = { statuses.add(it) },
            onLogLine = { logs.add(it) },
            pollIntervalMs = 20L,
            timeoutMs = 300L,
            stallTimeoutMs = 300L // soft warn at ~240ms
        )
        assertFailsWith<LocalLlmAgentSession.AgentTimeoutException> {
            session.run(goal = "soft warn", dryRun = true)
        }
        val soft = logs + statuses
        assertTrue(
            soft.any {
                it.contains("Budget warning", ignoreCase = true) ||
                    it.contains("budget used", ignoreCase = true) ||
                    it.contains("Stall warning", ignoreCase = true)
            },
            "expected soft budget warn in $soft"
        )
    }

    @Test
    fun `formatBudgetMs shows ms under one second`() {
        assertEquals("200ms", LocalLlmAgentSession.formatBudgetMs(200L))
        assertEquals("15s", LocalLlmAgentSession.formatBudgetMs(15_000L))
        assertEquals("2m", LocalLlmAgentSession.formatBudgetMs(120_000L))
    }

    @Test
    fun `formatPlanningStatus dry-run vs APPLY`() {
        assertEquals(
            "▸ Agent (dry-run) — planning…",
            LocalLlmAgentSession.formatPlanningStatus(dryRun = true)
        )
        assertEquals(
            "▸ Agent (APPLY) — planning…",
            LocalLlmAgentSession.formatPlanningStatus(dryRun = false)
        )
    }

    // --- attachmentPathsForAgent ---

    @Test
    fun `attachmentPathsForAgent empty list yields empty`() {
        assertEquals(emptyList(), LocalLlmAgentSession.attachmentPathsForAgent(emptyList()))
    }

    @Test
    fun `attachmentPathsForAgent prefers non-blank path over displayName`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "apps/retronium/main.go",
            displayName = "main.go",
            content = "package main"
        )
        assertEquals(
            listOf("apps/retronium/main.go"),
            LocalLlmAgentSession.attachmentPathsForAgent(listOf(att))
        )
    }

    @Test
    fun `attachmentPathsForAgent falls back to displayName when path blank`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.SELECTION,
            path = "  ",
            displayName = "selection.txt",
            content = "snippet"
        )
        assertEquals(
            listOf("selection.txt"),
            LocalLlmAgentSession.attachmentPathsForAgent(listOf(att))
        )
    }

    @Test
    fun `attachmentPathsForAgent falls back to chipLabel when path and displayName blank`() {
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = null,
            displayName = "   ",
            content = null
        )
        val paths = LocalLlmAgentSession.attachmentPathsForAgent(listOf(att))
        assertEquals(1, paths.size)
        assertTrue(paths.single().isNotBlank())
        assertEquals(att.chipLabel(), paths.single())
    }

    @Test
    fun `attachmentPathsForAgent keeps absolute path outside project as stored`() {
        val abs = "C:/Outside/other/file.txt"
        val att = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = abs,
            displayName = "file.txt"
        )
        assertEquals(listOf(abs), LocalLlmAgentSession.attachmentPathsForAgent(listOf(att)))
    }

    @Test
    fun `attachmentPathsForAgent dedupes and preserves multi-file order of first occurrence`() {
        val a = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "src/a.kt",
            displayName = "a.kt"
        )
        val b = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "src/b.kt",
            displayName = "b.kt"
        )
        val aDup = ContextAttachment(
            type = ContextAttachment.Type.FILE,
            path = "src/a.kt",
            displayName = "a.kt"
        )
        assertEquals(
            listOf("src/a.kt", "src/b.kt"),
            LocalLlmAgentSession.attachmentPathsForAgent(listOf(a, b, aDup))
        )
    }

    @Test
    fun `attachmentPathsForAgent never blank when chips had files`() {
        val files = listOf(
            ContextAttachment(ContextAttachment.Type.FILE, path = "pkg/x.go", displayName = "x.go"),
            ContextAttachment(ContextAttachment.Type.FILE, path = null, displayName = ""),
            ContextAttachment(ContextAttachment.Type.FILE, path = "  ", displayName = "  ")
        )
        val paths = LocalLlmAgentSession.attachmentPathsForAgent(files)
        // Real path kept; blank path/displayName fall back to chipLabel and may dedupe.
        assertTrue(paths.contains("pkg/x.go"))
        assertTrue(paths.any { it == "(attached file)" })
        paths.forEach { assertTrue(it.isNotBlank(), "path must be non-blank, got '$it'") }
    }

    // --- buildGoalWithAttachments ---

    @Test
    fun `buildGoalWithAttachments empty paths returns trimmed goal only`() {
        assertEquals(
            "Fix the bug",
            LocalLlmAgentSession.buildGoalWithAttachments("  Fix the bug  ", emptyList())
        )
        assertEquals(
            "Fix the bug",
            LocalLlmAgentSession.buildGoalWithAttachments("Fix the bug", listOf("  ", ""))
        )
    }

    @Test
    fun `buildGoalWithAttachments appends path list for read_file`() {
        val goal = LocalLlmAgentSession.buildGoalWithAttachments(
            userGoal = "Review these files",
            attachmentPaths = listOf("src/a.kt", "apps/retronium/main.go")
        )
        assertTrue(goal.startsWith("Review these files"))
        assertTrue(goal.contains("Attached paths (prefer read_file / workspace-relative):"))
        assertTrue(goal.contains("\n- src/a.kt"))
        assertTrue(goal.contains("\n- apps/retronium/main.go"))
    }

    @Test
    fun `buildGoalWithAttachments keeps user payload workspace context and adds paths`() {
        // Simulates agent path: payload already has AttachmentPayload blocks; paths still appended.
        val userPayload = buildString {
            append("Explain this")
            append("\n\n--- Workspace context ---\n")
            append("[Attached: src/ok.go]\n```\npackage main\n```")
        }
        val goal = LocalLlmAgentSession.buildGoalWithAttachments(
            userGoal = userPayload,
            attachmentPaths = listOf("src/ok.go")
        )
        assertTrue(goal.contains("--- Workspace context ---"))
        assertTrue(goal.contains("[Attached: src/ok.go]"))
        assertTrue(goal.contains("package main"))
        assertTrue(goal.contains("Attached paths (prefer read_file / workspace-relative):"))
        assertTrue(goal.contains("\n- src/ok.go"))
        // No empty fences introduced by goal builder
        assertFalse(goal.contains("```\n```"))
    }

    @Test
    fun `buildGoalWithAttachments dedupes blank-trimmed paths`() {
        val goal = LocalLlmAgentSession.buildGoalWithAttachments(
            "Go",
            listOf(" a.kt ", "a.kt", "", "b.kt")
        )
        assertEquals(1, goal.split("\n- a.kt").size - 1)
        assertTrue(goal.contains("\n- b.kt"))
    }

    @Test
    fun `buildGoalWithAttachments paths only when goal blank still yields path list`() {
        val goal = LocalLlmAgentSession.buildGoalWithAttachments(
            "   ",
            listOf("src/only.go")
        )
        assertFalse(goal.startsWith("\n"))
        assertTrue(goal.startsWith("Attached paths (prefer read_file / workspace-relative):"))
        assertTrue(goal.contains("\n- src/only.go"))
    }

    @Test
    fun `end-to-end pure agent path capture then goal`() {
        val attachments = listOf(
            ContextAttachment(
                type = ContextAttachment.Type.FILE,
                path = "pkg/store/fsstore.go",
                displayName = "fsstore.go",
                content = "package store"
            ),
            ContextAttachment(
                type = ContextAttachment.Type.FILE,
                path = "C:/Outside/bin/app.png",
                displayName = "app.png",
                content = null
            )
        )
        val paths = LocalLlmAgentSession.attachmentPathsForAgent(attachments)
        assertEquals(listOf("pkg/store/fsstore.go", "C:/Outside/bin/app.png"), paths)

        val payload = com.waryway.gab.ui.AttachmentPayload.buildMessagePayload(
            userText = "Audit attachments",
            attachments = attachments.map { att ->
                val label = att.path?.trim()?.takeIf { it.isNotEmpty() }
                    ?: att.displayName.trim().takeIf { it.isNotEmpty() }
                    ?: att.chipLabel()
                label to att.content
            }
        )
        assertTrue(payload.contains("[Attached: pkg/store/fsstore.go]"))
        assertTrue(payload.contains("```\npackage store\n```"))
        assertTrue(payload.contains("[Attached: C:/Outside/bin/app.png]"))
        assertTrue(payload.contains(com.waryway.gab.ui.AttachmentPayload.CONTENT_UNAVAILABLE))
        assertTrue(payload.contains("read_file"))
        assertFalse(payload.substringAfter("[Attached: C:/Outside/bin/app.png]").contains("```"))

        val goal = LocalLlmAgentSession.buildGoalWithAttachments(payload, paths)
        assertTrue(goal.contains("Audit attachments"))
        assertTrue(goal.contains("--- Workspace context ---"))
        assertTrue(goal.contains("\n- pkg/store/fsstore.go"))
        assertTrue(goal.contains("\n- C:/Outside/bin/app.png"))
        assertTrue(goal.contains("prefer read_file"))
    }
}

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
        events: List<AgentClient.AgentEvent> = emptyList()
    ) = AgentClient.AgentRun(
        id = id,
        goal = "goal",
        state = state,
        dryRun = dryRun,
        finalAnswer = finalAnswer,
        error = error,
        repoRoot = repoRoot,
        step = step,
        maxSteps = maxSteps,
        events = events
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

        override fun getRun(id: String): AgentClient.AgentRun {
            getIds.add(id)
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
        assertTrue(result.finalContent.startsWith("Already finished"))
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

    // --- formatFinalContent / formatTerminalStatus / formatStatusLine (wo-03-01) ---

    @Test
    fun `formatFinalContent done with finalAnswer dry-run preserves answer and badge`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = true, finalAnswer = "Plan: edit main.go", repoRoot = "/repo")
        )
        assertTrue(content.startsWith("Plan: edit main.go"))
        assertTrue(content.contains(LocalLlmAgentSession.DRY_RUN_BADGE.trim()))
        assertTrue(content.contains("repoRoot: /repo"))
        assertFalse(content.contains("Apply mode"))
    }

    @Test
    fun `formatFinalContent done with finalAnswer apply mode badge`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = false, finalAnswer = "  Applied fix  ")
        )
        assertTrue(content.startsWith("Applied fix"))
        assertTrue(content.contains(LocalLlmAgentSession.APPLY_BADGE.trim()))
        assertFalse(content.contains("Dry-run"))
    }

    @Test
    fun `formatFinalContent done empty finalAnswer is not blank success bubble`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = true, finalAnswer = null)
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("no final answer"))
        assertTrue(content.contains("state=done"))
        assertFalse(content.startsWith("Error:"))
    }

    @Test
    fun `formatFinalContent done blank finalAnswer is not blank`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "done", dryRun = false, finalAnswer = "   ")
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("no final answer"))
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
        // If server already produced answer before stop, surface it with badge rather than overwrite.
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "cancelled", dryRun = true, finalAnswer = "Halfway there"),
            userStopped = true
        )
        assertTrue(content.startsWith("Halfway there"))
        assertTrue(content.contains("Dry-run"))
    }

    @Test
    fun `formatFinalContent failed empty error still non-blank`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "failed", error = null, finalAnswer = null)
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("no final answer") || content.contains("failed"))
    }

    @Test
    fun `formatFinalContent odd blank state still non-blank`() {
        val content = LocalLlmAgentSession.formatFinalContent(
            run(state = "", error = null, finalAnswer = "  ")
        )
        assertTrue(content.isNotBlank())
        assertTrue(content.contains("unknown") || content.contains("no final answer"))
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

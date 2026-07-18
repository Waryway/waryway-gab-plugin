package com.waryway.gab.chat

import com.waryway.gab.client.AgentClient
import com.waryway.gab.diagnostics.SessionLog
import com.waryway.gab.model.ContextAttachment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal start/get/cancel surface for the Local LLM agent poll loop.
 * Production uses [AgentClient]; unit tests inject fakes (no HTTP).
 *
 * LOCAL_LLM `/api/agent` only — not used for GROK_BUILD / cloud chat.
 */
interface AgentRunOps {
    fun startRun(
        goal: String,
        dryRun: Boolean,
        preset: String?,
        model: String?,
        maxSteps: Int?
    ): AgentClient.AgentRun

    fun getRun(id: String): AgentClient.AgentRun

    fun cancelRun(id: String): AgentClient.AgentRun
}

/** Adapts [AgentClient] to [AgentRunOps]. */
fun AgentClient.asRunOps(): AgentRunOps = object : AgentRunOps {
    override fun startRun(
        goal: String,
        dryRun: Boolean,
        preset: String?,
        model: String?,
        maxSteps: Int?
    ): AgentClient.AgentRun = this@asRunOps.startRun(goal, dryRun, preset, model, maxSteps)

    override fun getRun(id: String): AgentClient.AgentRun = this@asRunOps.getRun(id)

    override fun cancelRun(id: String): AgentClient.AgentRun = this@asRunOps.cancelRun(id)
}

/**
 * Local LLM agent path: start/poll/cancel server /api/agent/runs.
 *
 * Mode split (important):
 * - LOCAL_LLM agent mode → this class + [AgentClient] (/api/agent plan+tool loop on the server).
 * - Cloud / Gab AI / Grok (and Local LLM chat-only) → [AgentSession] + [com.waryway.gab.client.GabClient]
 *   OpenAI multi-turn tool_calls / chat completions.
 *
 * Do not route Local LLM agent goals through [AgentSession]; the facade advertises
 * supportsTools=false and is chat-only.
 *
 * Poll loop talks to [ops] ([AgentRunOps]) so unit tests can inject a fake without HTTP.
 * Production constructors accept [AgentClient]. Routing gate remains tool-window
 * `provider == LOCAL_LLM && agentMode` (outside this class).
 */
class LocalLlmAgentSession(
    private val ops: AgentRunOps,
    private val sessionLog: SessionLog? = null,
    private val onStatus: (String) -> Unit = {},
    private val onLogLine: (String) -> Unit = {},
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
    private val pollIntervalMs: Long = 750L,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    /**
     * Production constructor: wraps [AgentClient] as [AgentRunOps].
     * Prefer this from the tool window; tests pass [AgentRunOps] fakes to the primary ctor.
     */
    constructor(
        client: AgentClient,
        sessionLog: SessionLog? = null,
        onStatus: (String) -> Unit = {},
        onLogLine: (String) -> Unit = {},
        cancelled: AtomicBoolean = AtomicBoolean(false),
        pollIntervalMs: Long = 750L,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ) : this(
        ops = client.asRunOps(),
        sessionLog = sessionLog,
        onStatus = onStatus,
        onLogLine = onLogLine,
        cancelled = cancelled,
        pollIntervalMs = pollIntervalMs,
        timeoutMs = timeoutMs
    )

    data class Result(
        val finalContent: String,
        val run: AgentClient.AgentRun,
        val toolCallCount: Int
    )

    private val activeRunId = AtomicReference<String?>(null)

    /** Active run id if a poll loop is in progress (for [cancelActiveRun]). */
    fun currentRunId(): String? = activeRunId.get()

    /**
     * Best-effort cancel of the active server run (and marks [cancelled]).
     * Safe to call from the UI Stop button.
     */
    fun cancelActiveRun() {
        cancelled.set(true)
        val id = activeRunId.get() ?: return
        try {
            ops.cancelRun(id)
            sessionLog?.system("agent cancel posted: $id")
        } catch (e: Exception) {
            sessionLog?.error("agent cancel failed: ${e.message}")
        }
    }

    /**
     * Start a run and poll until terminal, cancel, or timeout.
     * Blocks the calling thread (invoke from a background thread, not EDT).
     *
     * [dryRun] must be explicit — callers own the apply policy (default true in settings).
     */
    fun run(
        goal: String,
        dryRun: Boolean,
        preset: String? = "agent-plan",
        model: String? = null,
        maxSteps: Int? = null
    ): Result {
        require(goal.isNotBlank()) { "goal must not be blank" }

        sessionLog?.system(
            "local agent start dryRun=$dryRun preset=${preset.orEmpty()} " +
                "maxSteps=${maxSteps ?: 0} goalChars=${goal.length}"
        )
        onStatus(formatPlanningStatus(dryRun))

        val started = ops.startRun(
            goal = goal,
            dryRun = dryRun,
            preset = preset,
            model = model,
            maxSteps = maxSteps
        )
        activeRunId.set(started.id)
        onLogLine(formatRunHeader(started))
        if (started.repoRoot.isNotBlank()) {
            onLogLine("repoRoot: ${started.repoRoot}")
        }
        onStatus(statusLine(started))

        if (started.isTerminal) {
            return finish(started)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastEventCount = started.events.size
        var lastTaskFingerprint = taskFingerprint(started)
        var lastState = started.state
        var lastStep = started.step
        var snap = started

        while (true) {
            if (cancelled.get()) {
                try {
                    snap = ops.cancelRun(snap.id)
                } catch (_: Exception) {
                    // still exit cleanly
                }
                onLogLine("Agent run cancelled (id=${snap.id})")
                return finish(
                    snap.copy(state = snap.state.ifBlank { "cancelled" }),
                    userStopped = true
                )
            }
            if (System.currentTimeMillis() > deadline) {
                try {
                    ops.cancelRun(snap.id)
                } catch (_: Exception) {
                }
                activeRunId.set(null)
                val msg = "Agent run timed out after ${timeoutMs / 1000}s (id=${snap.id})"
                sessionLog?.error(msg)
                onLogLine(msg)
                throw AgentTimeoutException(msg, snap.id)
            }

            Thread.sleep(pollIntervalMs)

            snap = ops.getRun(snap.id)

            if (snap.state != lastState || snap.step != lastStep) {
                lastState = snap.state
                lastStep = snap.step
                onStatus(statusLine(snap))
            }

            val fp = taskFingerprint(snap)
            if (fp != lastTaskFingerprint) {
                lastTaskFingerprint = fp
                snap.plan?.let { plan ->
                    if (plan.summary.isNotBlank()) {
                        onLogLine("plan: ${plan.summary}")
                    }
                    plan.tasks.forEach { t ->
                        val toolName = t.tool?.takeIf { name -> name.isNotBlank() }
                        val toolSuffix = if (toolName != null) " [$toolName]" else ""
                        val st = t.status.ifBlank { "?" }
                        val title = t.title.ifBlank { t.id }.ifBlank { "task" }
                        onLogLine("  * $st - $title$toolSuffix")
                        val err = t.error?.takeIf { e -> e.isNotBlank() }
                        if (err != null) onLogLine("      error: $err")
                    }
                }
            }

            if (snap.events.size > lastEventCount) {
                snap.events.drop(lastEventCount).forEach { ev ->
                    onLogLine(formatEvent(ev))
                }
                lastEventCount = snap.events.size
            }

            if (snap.isTerminal) {
                return finish(snap)
            }
        }
    }

    private fun finish(run: AgentClient.AgentRun, userStopped: Boolean = false): Result {
        activeRunId.set(null)
        val tools = run.events.count { it.kind.equals("tool_call", ignoreCase = true) }
        onLogLine(
            "done: state=${run.state} dryRun=${run.dryRun} " +
                "step=${run.step}/${run.maxSteps} tools~$tools" +
                run.repoRoot.takeIf { it.isNotBlank() }?.let { " repoRoot=$it" }.orEmpty()
        )

        val content = formatFinalContent(run, userStopped)
        onStatus(formatTerminalStatus(run, userStopped))
        return Result(finalContent = content, run = run, toolCallCount = tools)
    }

    private fun statusLine(run: AgentClient.AgentRun): String = formatStatusLine(run)

    private fun formatRunHeader(run: AgentClient.AgentRun): String {
        val mode = if (run.dryRun) "DRY-RUN" else "APPLY"
        return "agent run ${run.id} · $mode · state=${run.state}" +
            run.preset.takeIf { it.isNotBlank() }?.let { " · preset=$it" }.orEmpty()
    }

    private fun formatEvent(ev: AgentClient.AgentEvent): String {
        val kind = ev.kind.ifBlank { "event" }
        val tool = ev.tool?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val detail = ev.detail.trim().replace('\n', ' ').take(240)
        return "  $kind$tool${if (detail.isNotEmpty()) ": $detail" else ""}"
    }

    private fun taskFingerprint(run: AgentClient.AgentRun): String {
        val tasks = run.plan?.tasks.orEmpty()
        return tasks.joinToString("|") { "${it.id}:${it.status}:${it.error.orEmpty().take(40)}" } +
            "#${run.plan?.summary.orEmpty().take(80)}"
    }

    class AgentTimeoutException(
        message: String,
        val runId: String
    ) : Exception(message)

    companion object {
        /** Default poll timeout (~8 minutes). */
        const val DEFAULT_TIMEOUT_MS: Long = 8L * 60L * 1000L

        /** Badge appended when a successful dry-run finishes with a final answer. */
        const val DRY_RUN_BADGE: String =
            "\n\n— Dry-run: planned/preview only (no workspace writes)."

        /** Badge appended when a successful apply-mode run finishes with a final answer. */
        const val APPLY_BADGE: String =
            "\n\n— Apply mode: mutating tools were allowed."

        const val STOPPED_BY_USER: String = "Stopped by user."

        /**
         * Pure terminal final content for a completed (or user-stopped) agent run.
         *
         * Always non-blank: empty [AgentClient.AgentRun.finalAnswer] never yields a blank
         * success bubble — uses a state-bearing placeholder or stop/error copy instead.
         */
        fun formatFinalContent(run: AgentClient.AgentRun, userStopped: Boolean = false): String {
            val answer = run.finalAnswer?.trim().orEmpty()
            val err = run.error?.trim().orEmpty()
            val content = when {
                userStopped && answer.isEmpty() -> STOPPED_BY_USER
                err.isNotEmpty() && run.state == "failed" ->
                    "Error: $err" + if (answer.isNotEmpty()) "\n\n$answer" else ""
                answer.isNotEmpty() -> {
                    val badge = if (run.dryRun) DRY_RUN_BADGE else APPLY_BADGE
                    val root = run.repoRoot.takeIf { it.isNotBlank() }?.let { "\n— repoRoot: $it" }.orEmpty()
                    answer + badge + root
                }
                run.state == "cancelled" || userStopped -> STOPPED_BY_USER
                err.isNotEmpty() -> "Error: $err"
                else -> "(agent finished with no final answer — state=${run.state.ifBlank { "unknown" }})"
            }
            return content.ifBlank {
                "(agent finished with no final answer — state=${run.state.ifBlank { "unknown" }})"
            }
        }

        /**
         * Pure terminal [onStatus] string after a run ends.
         * Reflects dry-run vs apply on done; maps user-stop to cancelled when state is not failed/done.
         */
        fun formatTerminalStatus(run: AgentClient.AgentRun, userStopped: Boolean = false): String {
            val state = when {
                userStopped && run.state != "done" && run.state != "failed" -> "cancelled"
                else -> run.state
            }
            return when (state) {
                "done" -> if (run.dryRun) "▸ Agent done (dry-run)" else "▸ Agent done (applied)"
                "failed" -> "▸ Agent failed"
                "cancelled" -> "▸ Agent cancelled"
                else -> "▸ Agent ${state.ifBlank { "finished" }}"
            }
        }

        /**
         * In-progress status line (poll updates): dry-run vs APPLY mode + step progress.
         */
        fun formatStatusLine(run: AgentClient.AgentRun): String {
            val mode = if (run.dryRun) "dry-run" else "APPLY"
            val steps = if (run.maxSteps > 0) " ${run.step}/${run.maxSteps}" else ""
            return "Agent [$mode] ${run.state}$steps"
        }

        /** Initial status when a run is about to start. */
        fun formatPlanningStatus(dryRun: Boolean): String =
            if (dryRun) "▸ Agent (dry-run) — planning…" else "▸ Agent (APPLY) — planning…"

        /**
         * Collect attachment paths for the Local LLM agent goal at Send time.
         *
         * Prefer [ContextAttachment.path] (project-relative when under base, absolute outside),
         * then non-blank [ContextAttachment.displayName], then [ContextAttachment.chipLabel]
         * so a chip that was visible never yields a blank entry.
         *
         * Call **before** [ConversationManager.clearAttachments]. Pure / unit-testable.
         */
        fun attachmentPathsForAgent(attachments: List<ContextAttachment>): List<String> {
            if (attachments.isEmpty()) return emptyList()
            return attachments.map { att ->
                att.path?.trim()?.takeIf { it.isNotEmpty() }
                    ?: att.displayName.trim().takeIf { it.isNotEmpty() }
                    ?: att.chipLabel()
            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        /**
         * Build an agent goal string from the chat user payload plus attachment paths.
         *
         * [userGoal] should already include workspace context blocks from
         * [com.waryway.gab.ui.AttachmentPayload.buildMessagePayload] (path + content, or
         * explicit content-unavailable / `read_file` instruction — never empty fences).
         * This helper appends a distinct path list so the server planner can call
         * `read_file` without re-duplicating file bodies.
         */
        fun buildGoalWithAttachments(userGoal: String, attachmentPaths: List<String>): String {
            val goal = userGoal.trim()
            val paths = attachmentPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (paths.isEmpty()) return goal
            return buildString {
                if (goal.isNotEmpty()) {
                    append(goal)
                    append("\n\n")
                }
                append("Attached paths (prefer read_file / workspace-relative):")
                paths.forEach { append("\n- ").append(it) }
            }
        }
    }
}

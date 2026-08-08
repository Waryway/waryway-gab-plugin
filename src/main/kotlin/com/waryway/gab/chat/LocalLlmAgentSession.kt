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

    /** [quiet] true = no per-poll HTTP log (session logs on change only). */
    fun getRun(id: String, quiet: Boolean = false): AgentClient.AgentRun

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

    override fun getRun(id: String, quiet: Boolean): AgentClient.AgentRun =
        this@asRunOps.getRun(id, quiet = quiet)

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
    /**
     * Full tool_result body for the chat UI (collapsed expandable blocks).
     * [label] is the tool name (or kind+tool); [output] is the full event detail.
     * Default no-op so existing call sites compile unchanged.
     */
    private val onCommandOutput: (label: String, output: String) -> Unit = { _, _ -> },
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * Max time without state/step/message progress before stall-timeout.
     * 0 = derive from [timeoutMs] ([DEFAULT_STALL_FRACTION], min [MIN_STALL_TIMEOUT_MS]).
     * Progress resets the stall clock; absolute [timeoutMs] still caps the whole run.
     */
    private val stallTimeoutMs: Long = 0L
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
        onCommandOutput: (label: String, output: String) -> Unit = { _, _ -> },
        cancelled: AtomicBoolean = AtomicBoolean(false),
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        stallTimeoutMs: Long = 0L
    ) : this(
        ops = client.asRunOps(),
        sessionLog = sessionLog,
        onStatus = onStatus,
        onLogLine = onLogLine,
        onCommandOutput = onCommandOutput,
        cancelled = cancelled,
        pollIntervalMs = pollIntervalMs,
        timeoutMs = timeoutMs,
        stallTimeoutMs = stallTimeoutMs
    )

    data class Result(
        /**
         * Full plain-text body for history / copy (answer + thinking + footer).
         * Always non-blank on success paths that use hierarchical layout.
         */
        val finalContent: String,
        val run: AgentClient.AgentRun,
        val toolCallCount: Int,
        /**
         * Operator-facing answer only (no goal/plan/footer). When non-null, the chat UI
         * should show this as the main bubble body and [displayThinking] separately.
         * Null for error / stop single-line contracts.
         */
        val displayAnswer: String? = null,
        /**
         * Goal / plan / task checklist for a collapsible "Thinking" panel.
         * Null when there is nothing useful beyond the answer.
         */
        val displayThinking: String? = null,
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

        val startedAt = System.currentTimeMillis()
        val absoluteDeadline = startedAt + timeoutMs
        val effectiveStallMs = resolveStallTimeoutMs(timeoutMs, stallTimeoutMs)
        var lastEventCount = started.events.size
        var lastTaskFingerprint = taskFingerprint(started)
        var lastState = started.state
        var lastStep = started.step
        var lastMessage = started.message.orEmpty()
        var lastHeartbeatAt = startedAt
        var sameStateSince = startedAt
        var lastProgressAt = startedAt
        var currentPollMs = pollIntervalMs.coerceAtLeast(250L)
        var snap = started
        var softAbsoluteWarned = false
        var softStallWarned = false

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
            val now = System.currentTimeMillis()
            val absoluteHit = now > absoluteDeadline
            val stallHit = now - lastProgressAt > effectiveStallMs
            if (absoluteHit || stallHit) {
                try {
                    ops.cancelRun(snap.id)
                } catch (_: Exception) {
                }
                activeRunId.set(null)
                val elapsed = (now - startedAt) / 1000
                val stallSec = (now - lastProgressAt) / 1000
                val reason = when {
                    absoluteHit && stallHit -> "absolute+stall"
                    absoluteHit -> "absolute"
                    else -> "stall"
                }
                val budgetMs = if (absoluteHit) timeoutMs else effectiveStallMs
                // Prefer any finalAnswer / error the server already wrote before cancel.
                val partial = snap.finalAnswer?.trim().orEmpty()
                val msg =
                    "Agent run timed out after ${formatBudgetMs(budgetMs)} ($reason; id=${snap.id}, " +
                        "state=${snap.state}, step=${snap.step}/${snap.maxSteps}, " +
                        "elapsed=${elapsed}s, no_progress=${stallSec}s). " +
                        if (reason.contains("stall") && !absoluteHit) {
                            "No state/step/message progress for ${stallSec}s — server may be stuck in planning. " +
                                "Raise Agent poll timeout, use Chat for Q&A, or switch to cpp/vulkan."
                        } else {
                            "Hit the full agent poll budget (${formatBudgetMs(timeoutMs)}). " +
                                "Raise Settings → Agent poll timeout or use Chat for Q&A."
                        }
                sessionLog?.error(msg)
                onLogLine(msg)
                throw AgentTimeoutException(msg, snap.id, reason = reason, partialContent = partial)
            }

            // Soft warnings before hard timeout so operators can Stop or wait knowingly.
            // Use ms thresholds (not whole seconds) so short budgets still warn.
            val elapsedMs = now - startedAt
            val softAbsAt = (timeoutMs * SOFT_WARN_FRACTION).toLong().coerceAtLeast(1L)
            if (!softAbsoluteWarned && elapsedMs >= softAbsAt) {
                softAbsoluteWarned = true
                val remainMs = (absoluteDeadline - now).coerceAtLeast(0)
                onLogLine(
                    "… ${formatBudgetMs(elapsedMs)} of ${formatBudgetMs(timeoutMs)} absolute budget used " +
                        "(~${formatBudgetMs(remainMs)} left) — still ${snap.state} " +
                        "step=${snap.step}/${snap.maxSteps}"
                )
                onStatus(
                    "▸ Budget warning — ~${formatBudgetMs(remainMs)} of agent poll timeout left"
                )
            }
            val noProgressMs = now - lastProgressAt
            val softStallAt = (effectiveStallMs * SOFT_WARN_FRACTION).toLong().coerceAtLeast(1L)
            if (!softStallWarned && noProgressMs >= softStallAt) {
                softStallWarned = true
                val stallRemainMs = (effectiveStallMs - noProgressMs).coerceAtLeast(0)
                onLogLine(
                    "… no progress for ${formatBudgetMs(noProgressMs)} " +
                        "(stall budget ${formatBudgetMs(effectiveStallMs)}, " +
                        "~${formatBudgetMs(stallRemainMs)} left) — still ${snap.state}"
                )
                onStatus(
                    "▸ Stall warning — no progress for ${formatBudgetMs(noProgressMs)} " +
                        "(~${formatBudgetMs(stallRemainMs)} until stall timeout)"
                )
            }

            try {
                Thread.sleep(currentPollMs)
            } catch (_: InterruptedException) {
                // Stop button interrupts the worker — treat as cancel, not a hard error.
                Thread.currentThread().interrupt()
                if (cancelled.get()) {
                    try {
                        snap = ops.cancelRun(snap.id)
                    } catch (_: Exception) {
                    }
                    onLogLine("Agent run cancelled (id=${snap.id})")
                    return finish(
                        snap.copy(state = snap.state.ifBlank { "cancelled" }),
                        userStopped = true
                    )
                }
                // Unexpected interrupt without cancel flag — rethrow as clean stop.
                try {
                    ops.cancelRun(snap.id)
                } catch (_: Exception) {
                }
                return finish(
                    snap.copy(state = "cancelled"),
                    userStopped = true
                )
            }

            // Quiet poll: avoid flooding session log with identical planning GETs.
            snap = ops.getRun(snap.id, quiet = true)

            val stateChanged = snap.state != lastState || snap.step != lastStep
            val msgNow = snap.message.orEmpty()
            val messageChanged = msgNow.isNotBlank() && msgNow != lastMessage

            if (stateChanged || messageChanged) {
                val progressNow = System.currentTimeMillis()
                lastProgressAt = progressNow
                if (stateChanged) {
                    sameStateSince = progressNow
                    // Reset to snappier interval when progress resumes.
                    currentPollMs = pollIntervalMs.coerceAtLeast(250L)
                }
                lastState = snap.state
                lastStep = snap.step
                if (messageChanged) lastMessage = msgNow
                onStatus(statusLine(snap))
                sessionLog?.system(
                    "agent progress: id=${snap.id} state=${snap.state} " +
                        "step=${snap.step}/${snap.maxSteps}" +
                        msgNow.takeIf { it.isNotBlank() }?.let { " msg=$it" }.orEmpty()
                )
            } else {
                // Back off while stuck (e.g. long go-cpu plan Generate).
                val stuckMs = System.currentTimeMillis() - sameStateSince
                if (stuckMs > 5_000L) {
                    currentPollMs = (currentPollMs * 2).coerceAtMost(MAX_POLL_INTERVAL_MS)
                }
            }

            // Events / plan task status changes also count as progress (stall clock).
            val fpNow = taskFingerprint(snap)
            if (fpNow != lastTaskFingerprint || snap.events.size > lastEventCount) {
                lastProgressAt = System.currentTimeMillis()
            }

            // Heartbeat so the UI shows the model is still working without per-second spam.
            val hbNow = System.currentTimeMillis()
            if (hbNow - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatAt = hbNow
                val elapsedSec = (hbNow - startedAt) / 1000
                val stuckSec = (hbNow - sameStateSince) / 1000
                val absRemain = ((absoluteDeadline - hbNow) / 1000).coerceAtLeast(0)
                val stallRemain =
                    ((effectiveStallMs - (hbNow - lastProgressAt)) / 1000).coerceAtLeast(0)
                onLogLine(
                    "… still ${snap.state} (${elapsedSec}s elapsed, ${stuckSec}s in this state; " +
                        "budget ~${absRemain}s abs / ~${stallRemain}s stall left" +
                        msgNow.takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty() +
                        "). Local go-cpu may take several minutes for planning."
                )
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
                    emitEvent(ev)
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

        val parts = formatFinalDisplayParts(run, userStopped)
        onStatus(formatTerminalStatus(run, userStopped))
        return Result(
            finalContent = parts.fullText,
            run = run,
            toolCallCount = tools,
            displayAnswer = parts.answer,
            displayThinking = parts.thinking,
        )
    }

    private fun statusLine(run: AgentClient.AgentRun): String = formatStatusLine(run)

    private fun formatRunHeader(run: AgentClient.AgentRun): String {
        val mode = if (run.dryRun) "DRY-RUN" else "APPLY"
        return "agent run ${run.id} · $mode · state=${run.state}" +
            run.preset.takeIf { it.isNotBlank() }?.let { " · preset=$it" }.orEmpty()
    }

    private fun formatEvent(ev: AgentClient.AgentEvent): String = formatEventSummary(ev)

    /**
     * Fan out a new run event: short [onLogLine] always; large [tool_result] also
     * emits full detail via [onCommandOutput] and logs the body to [sessionLog].
     */
    private fun emitEvent(ev: AgentClient.AgentEvent) {
        val summary = formatEvent(ev)
        if (shouldEmitCommandOutput(ev)) {
            val label = commandOutputLabel(ev)
            val fullDetail = ev.detail
            // Activity log keeps full body (soft-capped) when chat gets a collapsible.
            val logBody = if (fullDetail.length > COMMAND_OUTPUT_LOG_SOFT_CAP) {
                fullDetail.take(COMMAND_OUTPUT_LOG_SOFT_CAP) +
                    "\n… (${fullDetail.length - COMMAND_OUTPUT_LOG_SOFT_CAP} more chars)"
            } else {
                fullDetail
            }
            sessionLog?.tool("result $label:\n$logBody")
            onCommandOutput(label, fullDetail)
        }
        onLogLine(summary)
    }

    private fun taskFingerprint(run: AgentClient.AgentRun): String {
        val tasks = run.plan?.tasks.orEmpty()
        return tasks.joinToString("|") { "${it.id}:${it.status}:${it.error.orEmpty().take(40)}" } +
            "#${run.plan?.summary.orEmpty().take(80)}"
    }

    class AgentTimeoutException(
        message: String,
        val runId: String,
        /** `absolute`, `stall`, or `absolute+stall`. */
        val reason: String = "absolute",
        /** Any finalAnswer the server had before cancel (rare mid-timeout). */
        val partialContent: String? = null
    ) : Exception(message)

    companion object {
        /**
         * Default poll timeout (~30 minutes). Pure-Go go-cpu plan+tools can exceed 8 minutes;
         * server now falls back to heuristic plans after ~90s, but tools/summarize still need headroom.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 30L * 60L * 1000L

        /**
         * When no explicit stall timeout is set, stall = max(minStall, fraction * absolute).
         * Stuck planning fails sooner with a clear message; progressive runs still get full budget.
         */
        const val DEFAULT_STALL_FRACTION: Double = 0.4

        /** Soft onLogLine/onStatus when this fraction of absolute or stall budget is used. */
        const val SOFT_WARN_FRACTION: Double = 0.8

        /** Floor for derived stall timeout (10 minutes). */
        const val MIN_STALL_TIMEOUT_MS: Long = 10L * 60L * 1000L

        /** Cap for derived stall (never longer than absolute budget). */
        const val MAX_STALL_TIMEOUT_MS: Long = 45L * 60L * 1000L

        /** Initial poll interval; backs off while state is unchanged. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1_500L

        /** Cap for adaptive poll backoff while stuck in planning/executing. */
        const val MAX_POLL_INTERVAL_MS: Long = 5_000L

        /** User-visible heartbeat while still non-terminal (not every GET). */
        const val HEARTBEAT_INTERVAL_MS: Long = 15_000L

        // --- Progress stream formatting (status / chat timeline lines) ---

        /** Overall cap for a single progress status summary line. */
        const val PROGRESS_SUMMARY_MAX_CHARS: Int = 160

        /** Cap for the first-line / first-tokens snippet of multi-line detail. */
        const val PROGRESS_SNIPPET_MAX_CHARS: Int = 80

        /** Soft multi-line preview: max non-blank lines preserved. */
        const val PROGRESS_PREVIEW_MAX_LINES: Int = 3

        /** Soft multi-line preview: total character budget. */
        const val PROGRESS_PREVIEW_MAX_CHARS: Int = 200

        // --- Collapsible tool_result thresholds (onCommandOutput) ---

        /**
         * Emit collapsible command output when non-blank line count exceeds this
         * (OR when detail length exceeds [COMMAND_OUTPUT_MIN_CHARS]).
         */
        const val COMMAND_OUTPUT_MIN_NON_BLANK_LINES: Int = 2

        /**
         * Emit collapsible command output when detail length exceeds this
         * (OR when non-blank lines exceed [COMMAND_OUTPUT_MIN_NON_BLANK_LINES]).
         */
        const val COMMAND_OUTPUT_MIN_CHARS: Int = 200

        /** Soft-cap for full tool_result body written to Activity log (AgentSession-style). */
        const val COMMAND_OUTPUT_LOG_SOFT_CAP: Int = 4_000

        /**
         * Whether [detail] is large enough for a collapsible command-output panel.
         * True when non-blank lines > [COMMAND_OUTPUT_MIN_NON_BLANK_LINES] OR
         * char length > [COMMAND_OUTPUT_MIN_CHARS].
         */
        fun isLargeToolResultDetail(detail: String): Boolean {
            if (detail.length > COMMAND_OUTPUT_MIN_CHARS) return true
            return progressNonBlankLineCount(detail) > COMMAND_OUTPUT_MIN_NON_BLANK_LINES
        }

        /**
         * True when [ev] is a `tool_result` (case-insensitive) whose detail exceeds
         * the collapsible threshold — i.e. should invoke [onCommandOutput].
         */
        fun shouldEmitCommandOutput(ev: AgentClient.AgentEvent): Boolean {
            if (!ev.kind.equals("tool_result", ignoreCase = true)) return false
            return isLargeToolResultDetail(ev.detail)
        }

        /**
         * Label for collapsible header: prefer tool name; fall back to kind + optional task id.
         */
        fun commandOutputLabel(ev: AgentClient.AgentEvent): String {
            val tool = ev.tool?.takeIf { it.isNotBlank() }
            if (tool != null) return tool
            val task = ev.taskId?.takeIf { it.isNotBlank() }
            return if (task != null) "tool_result $task" else "tool_result"
        }

        /**
         * Chat/status-safe progress line for an agent event.
         *
         * Multi-line tool details become a short summary (kind, tool, line/char stats,
         * first-line snippet) — never a space-joined dump of the full payload.
         * Short single-line details stay concise; empty detail has no trailing colon.
         */
        fun formatEventSummary(ev: AgentClient.AgentEvent): String {
            val kind = ev.kind.ifBlank { "event" }
            val tool = ev.tool?.takeIf { it.isNotBlank() }.orEmpty()
            val toolPart = if (tool.isNotEmpty()) " $tool" else ""
            val detail = ev.detail.trim()
            if (detail.isEmpty()) {
                return "  $kind$toolPart"
            }

            val nonBlank = progressNonBlankLines(detail)
            return if (nonBlank.size > 1) {
                progressFormatMultiLineSummary(kind, tool, detail, nonBlank)
            } else {
                progressFormatSingleLine(kind, toolPart, detail)
            }
        }

        /** Non-blank lines of [detail] (trailing whitespace stripped per line). */
        fun progressNonBlankLines(detail: String): List<String> =
            detail.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .toList()

        /** Count of non-blank lines in [detail]. */
        fun progressNonBlankLineCount(detail: String): Int = progressNonBlankLines(detail).size

        /** First non-blank line of [detail], trimmed; empty if none. */
        fun progressFirstNonBlankLine(detail: String): String =
            detail.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

        /**
         * Truncate a single line/snippet for progress display (no newline flattening of a body).
         * Adds an ellipsis when truncated.
         */
        fun progressDetailSnippet(
            line: String,
            maxChars: Int = PROGRESS_SNIPPET_MAX_CHARS
        ): String {
            val t = line.trim().replace('\n', ' ').replace('\r', ' ')
            if (t.isEmpty()) return ""
            val cap = maxChars.coerceAtLeast(1)
            return if (t.length <= cap) t else t.take((cap - 1).coerceAtLeast(0)) + "…"
        }

        /**
         * Human-readable character count for progress stats (`42 chars`, `1.2k chars`).
         */
        fun progressFormatCharCount(n: Int): String {
            val count = n.coerceAtLeast(0)
            if (count < 1000) return "$count chars"
            // One decimal for 1.0k–9.9k; whole k thereafter.
            if (count < 10_000) {
                val tenths = (count + 50) / 100
                val whole = tenths / 10
                val frac = tenths % 10
                return if (frac == 0) "${whole}k chars" else "$whole.${frac}k chars"
            }
            return "${(count + 500) / 1000}k chars"
        }

        /**
         * Optional structured multi-line preview (capped lines + chars).
         * Preserves newlines; not used by default [formatEventSummary] (one-line summary preferred).
         */
        fun progressFormatPreview(detail: String): String {
            val lines = progressNonBlankLines(detail).take(PROGRESS_PREVIEW_MAX_LINES)
            if (lines.isEmpty()) return ""
            val joined = lines.joinToString("\n")
            return if (joined.length <= PROGRESS_PREVIEW_MAX_CHARS) {
                joined
            } else {
                joined.take(PROGRESS_PREVIEW_MAX_CHARS - 1) + "…"
            }
        }

        /**
         * Multi-line progress summary:
         * `  tool_result list_directory_tree · 42 lines · 1.2k chars — AGENTS.md BUILD.bazel …`
         */
        fun progressFormatMultiLineSummary(
            kind: String,
            tool: String,
            detail: String,
            nonBlankLines: List<String> = progressNonBlankLines(detail)
        ): String {
            val lineCount = nonBlankLines.size.coerceAtLeast(progressNonBlankLineCount(detail))
            val charLabel = progressFormatCharCount(detail.length)
            val toolPart = if (tool.isNotBlank()) " $tool" else ""
            val snippet = progressDetailSnippet(
                nonBlankLines.firstOrNull() ?: progressFirstNonBlankLine(detail)
            )
            val stats = " · $lineCount lines · $charLabel"
            val base = "  $kind$toolPart$stats"
            val withSnippet = if (snippet.isNotEmpty()) "$base — $snippet" else base
            return if (withSnippet.length <= PROGRESS_SUMMARY_MAX_CHARS) {
                withSnippet
            } else {
                withSnippet.take(PROGRESS_SUMMARY_MAX_CHARS - 1) + "…"
            }
        }

        /** Single-line (or effectively single non-blank) detail: concise with soft total cap. */
        private fun progressFormatSingleLine(kind: String, toolPart: String, detail: String): String {
            // Collapse only residual newlines in an otherwise single-line payload.
            val flat = detail.replace('\n', ' ').replace('\r', ' ').trim()
            if (flat.isEmpty()) return "  $kind$toolPart"
            val prefix = "  $kind$toolPart: "
            val maxDetail = (PROGRESS_SUMMARY_MAX_CHARS - prefix.length).coerceAtLeast(40)
            val body = if (flat.length <= maxDetail) flat else flat.take(maxDetail - 1) + "…"
            return prefix + body
        }

        /** Human budget label: sub-second as ms, else seconds (or m/s via whole seconds). */
        fun formatBudgetMs(ms: Long): String {
            if (ms < 1000L) return "${ms.coerceAtLeast(0)}ms"
            val sec = ms / 1000L
            return if (sec < 60L) "${sec}s" else {
                val m = sec / 60L
                val s = sec % 60L
                if (s == 0L) "${m}m" else "${m}m ${s}s"
            }
        }

        /**
         * Resolve stall budget: explicit [stallTimeoutMs] if > 0, else fraction of absolute
         * with [MIN_STALL_TIMEOUT_MS] floor (and never above absolute).
         */
        fun resolveStallTimeoutMs(timeoutMs: Long, stallTimeoutMs: Long = 0L): Long {
            val absolute = timeoutMs.coerceAtLeast(1L)
            if (stallTimeoutMs > 0L) {
                // Explicit stall: clamp to absolute only (allow sub-second in tests).
                return stallTimeoutMs.coerceIn(1L, absolute)
            }
            // Derived: floor 10m in production budgets; never exceed absolute.
            val derived = (absolute * DEFAULT_STALL_FRACTION).toLong()
                .coerceAtLeast(MIN_STALL_TIMEOUT_MS.coerceAtMost(absolute))
                .coerceAtMost(MAX_STALL_TIMEOUT_MS)
            return derived.coerceAtMost(absolute)
        }

        /** Badge appended when a successful dry-run finishes with a final answer. */
        const val DRY_RUN_BADGE: String =
            "\n\n— Dry-run: planned/preview only (no workspace writes)."

        /** Badge appended when a successful apply-mode run finishes with a final answer. */
        const val APPLY_BADGE: String =
            "\n\n— Apply mode: mutating tools were allowed."

        const val STOPPED_BY_USER: String = "Stopped by user."

        /** Soft-cap for condensed answer body in the hierarchical final bubble. */
        const val FINAL_ANSWER_SOFT_CAP: Int = 2_400

        /** Soft-cap for per-task result/error bodies under Task evidence. */
        const val FINAL_TASK_EVIDENCE_CAP: Int = 600

        /** Max chars kept per file excerpt when condensing multi-file read dumps. */
        const val FINAL_ANSWER_PER_FILE_EXCERPT: Int = 420

        /**
         * Soft-cap [text] to [maxChars], appending an explicit truncation marker when cut.
         * Marker form: `… [truncated for UI — N more chars]`.
         */
        fun softCapFinalText(text: String, maxChars: Int): String {
            if (maxChars <= 0) return text
            if (text.length <= maxChars) return text
            val omitted = text.length - maxChars
            return text.take(maxChars).trimEnd() + "\n… [truncated for UI — $omitted more chars]"
        }

        /** Count `tool_call` events on [run] (same rule as [finish]). */
        fun countFinalToolCalls(run: AgentClient.AgentRun): Int =
            run.events.count { it.kind.equals("tool_call", ignoreCase = true) }

        /** Status/mode header line for hierarchical final content. */
        fun formatFinalHeader(run: AgentClient.AgentRun): String {
            val mode = if (run.dryRun) "DRY-RUN" else "APPLY"
            val state = run.state.ifBlank { "unknown" }
            return "### Agent result — $state · $mode"
        }

        /** Goal + plan summary + task checklist for hierarchical final content. */
        fun formatFinalGoalAndPlan(run: AgentClient.AgentRun): String = buildString {
            val goal = run.goal.trim().ifBlank { "(no goal)" }
            append("**Goal:** ").append(goal)
            val plan = run.plan
            if (plan != null) {
                val summary = plan.summary.trim()
                if (summary.isNotEmpty()) {
                    append("\n**Plan:** ").append(summary)
                } else if (plan.tasks.isNotEmpty()) {
                    append("\n**Plan:**")
                }
                for (task in plan.tasks) {
                    val status = task.status.ifBlank { "?" }
                    val title = task.title.ifBlank { task.id.ifBlank { "(task)" } }
                    val tool = task.tool?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                    append("\n- [").append(status).append("] ").append(title).append(tool)
                }
            }
        }

        /**
         * Thinking block for the final bubble: mode header + goal/plan checklist.
         * Shown under a collapsible "Thinking" panel in the UI, separate from Answer.
         */
        fun formatFinalThinkingSection(run: AgentClient.AgentRun): String = buildString {
            append(formatFinalHeader(run))
            append('\n')
            append(formatFinalGoalAndPlan(run))
        }

        /**
         * Extract operator-facing answer from server [finalAnswer].
         *
         * Prefers the body after `Answer:` (LocalLLM fallbackSummary) and drops
         * trailing `---` meta / `### [done]` task dumps so the bubble leads with
         * file substance (e.g. quanta README) instead of Goal/Plan noise.
         */
        fun extractPrimaryAnswer(raw: String): String {
            val text = raw.trim()
            if (text.isEmpty()) return text
            val markers = listOf("\nAnswer:\n", "\nAnswer:\r\n", "Answer:\n", "Answer:\r\n")
            var body = text
            for (m in markers) {
                val idx = text.indexOf(m)
                if (idx >= 0) {
                    body = text.substring(idx + m.length).trimStart()
                    break
                }
                // Whole string is "Answer:\n..."
                if (text.startsWith("Answer:")) {
                    body = text.removePrefix("Answer:").trimStart()
                    break
                }
            }
            // Cut server meta / task sections after the answer body.
            val cutMarkers = listOf("\n---\n", "\n### [", "\n— ", "\nGoal: ", "\nPlan: ")
            var cutAt = body.length
            for (m in cutMarkers) {
                val i = body.indexOf(m)
                // Keep first path line + content; only cut if marker appears after some substance.
                if (i > 40 && i < cutAt) cutAt = i
            }
            body = body.take(cutAt).trimEnd()
            // Drop numbered "1|# Title" prefixes if still present.
            body = body.lineSequence().joinToString("\n") { line ->
                val pipe = line.indexOf('|')
                if (pipe in 1..6 && line.substring(0, pipe).all { it.isDigit() }) {
                    line.substring(pipe + 1)
                } else line
            }.trimEnd()
            return body.ifBlank { text }
        }

        /**
         * True when [line] looks like a file path header from LocalLLM read dumps
         * (e.g. `AGENTS.md`, `apps/guide/README.md`, `pkg\localllm\agent\parse.go`).
         */
        fun looksLikeFilePathLine(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty() || t.length > 180) return false
            if (t.startsWith("#") || t.startsWith("-") || t.startsWith("*") || t.startsWith(">")) return false
            if (t.startsWith("**") || t.startsWith("```")) return false
            if (t.contains(' ') && !t.contains('/') && !t.contains('\\')) return false
            // extension present, or has path separators
            val hasExt = Regex("""\.[A-Za-z0-9]{1,8}$""").containsMatchIn(t)
            val hasSep = t.contains('/') || t.contains('\\')
            if (!hasExt && !hasSep) return false
            // Reject sentences / plan noise.
            if (t.endsWith('.') || t.endsWith('?') || t.endsWith('!')) return false
            if (t.startsWith("Goal:", ignoreCase = true) || t.startsWith("Plan:", ignoreCase = true)) return false
            return Regex("""^[\w./\\:@\-]+$""").matches(t)
        }

        /**
         * First useful prose excerpt from a file body (skip # titles / blank lines).
         */
        fun excerptFileBody(body: String, maxChars: Int = FINAL_ANSWER_PER_FILE_EXCERPT): String {
            val lines = body.lineSequence().map { it.trimEnd() }.toList()
            val picked = mutableListOf<String>()
            var chars = 0
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty()) {
                    if (picked.isNotEmpty()) break
                    continue
                }
                // Skip pure heading lines after we already have a title line once.
                if (t.startsWith("#") && picked.isEmpty()) {
                    val title = t.trimStart('#').trim()
                    if (title.isNotEmpty()) {
                        picked.add(title)
                        chars += title.length
                    }
                    continue
                }
                if (t.startsWith("#")) continue
                picked.add(t)
                chars += t.length + 1
                if (chars >= maxChars) break
                // Stop after first solid paragraph (~2–4 lines of prose / bullets).
                if (picked.size >= 4 && chars >= 120) break
            }
            val joined = picked.joinToString("\n").trim()
            if (joined.isEmpty()) {
                return softCapFinalText(body.trim(), maxChars)
            }
            return if (joined.length <= maxChars) joined
            else joined.take(maxChars).trimEnd() + "…"
        }

        /**
         * Condense multi-file read dumps into path + short excerpts so the Answer
         * is scannable. Full file bodies stay in the tool timeline collapsibles.
         *
         * Single short prose answers pass through unchanged (soft-capped by caller).
         */
        fun condenseAnswerForDisplay(answerBody: String): String {
            val text = answerBody.trim()
            if (text.isEmpty()) return text
            // Already short / not a dump — keep.
            if (text.length <= FINAL_ANSWER_SOFT_CAP && !looksLikeMultiFileDump(text)) {
                return text
            }
            val blocks = splitFileDumpBlocks(text)
            if (blocks.isEmpty()) {
                return softCapFinalText(text, FINAL_ANSWER_SOFT_CAP)
            }
            // Prefer README-like files first for identity-style dumps (still show all).
            val ordered = blocks.sortedBy { (path, _) ->
                val lower = path.lowercase()
                when {
                    lower.endsWith("readme.md") || lower == "readme.md" -> 0
                    lower.endsWith("readme") -> 1
                    lower.contains("agents.md") -> 2
                    else -> 3
                }
            }
            return buildString {
                if (ordered.size > 1) {
                    append("From ${ordered.size} files (excerpts — full text in tool output above):\n")
                }
                for ((i, block) in ordered.withIndex()) {
                    val (path, body) = block
                    if (i > 0) append("\n\n")
                    if (path.isNotBlank()) {
                        append("**").append(path).append("**\n")
                    }
                    append(excerptFileBody(body))
                }
            }.trim()
        }

        private fun looksLikeMultiFileDump(text: String): Boolean {
            val pathLines = text.lineSequence().count { looksLikeFilePathLine(it) }
            // Multiple path headers, or one path + large body.
            if (pathLines >= 2) return true
            if (pathLines == 1 && text.length > 900) return true
            return false
        }

        /**
         * Split a concatenated read dump into (path, body) pairs.
         * Returns empty when no path headers are detected.
         */
        fun splitFileDumpBlocks(text: String): List<Pair<String, String>> {
            val lines = text.lines()
            val blocks = mutableListOf<Pair<String, String>>()
            var path: String? = null
            val buf = StringBuilder()
            fun flush() {
                val p = path
                if (p != null) {
                    blocks.add(p to buf.toString().trim())
                }
                path = null
                buf.setLength(0)
            }
            for (line in lines) {
                if (looksLikeFilePathLine(line)) {
                    flush()
                    path = line.trim()
                } else if (path != null) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                } else {
                    // Leading prose before first path — treat as untitled block if later paths appear.
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                }
            }
            flush()
            // Leading prose with no paths → not a file dump structure.
            if (blocks.isEmpty()) return emptyList()
            // If we only captured one path and a huge body, still return it for condensation.
            return blocks
        }

        /**
         * Answer section: soft-caps large bodies so tool dumps do not dominate the bubble.
         * [answerBody] is already chosen (extracted + condensed primary answer or placeholder).
         */
        fun formatFinalAnswerSection(answerBody: String): String {
            val body = softCapFinalText(answerBody, FINAL_ANSWER_SOFT_CAP)
            return "### Answer\n\n$body"
        }

        /**
         * Per-task evidence blocks (capped result/error). Empty when there are no plan tasks.
         * Skips successful read_file tasks whose body is already in the Answer section.
         */
        fun formatFinalTaskEvidence(run: AgentClient.AgentRun, skipSuccessfulReads: Boolean = true): String {
            val tasks = run.plan?.tasks.orEmpty()
            if (tasks.isEmpty()) return ""
            val filtered = tasks.filterNot {
                skipSuccessfulReads &&
                    it.tool.equals("read_file", ignoreCase = true) &&
                    it.status.equals("done", ignoreCase = true)
            }
            if (filtered.isEmpty()) return ""
            return buildString {
                append("### Details")
                for (task in filtered) {
                    val status = task.status.ifBlank { "?" }
                    val title = task.title.ifBlank { task.id.ifBlank { "(task)" } }
                    append("\n#### [").append(status).append("] ").append(title)
                    val err = task.error?.trim().orEmpty()
                    val result = task.result?.trim().orEmpty()
                    val evidence = when {
                        err.isNotEmpty() -> err
                        result.isNotEmpty() -> result
                        else -> "(no result)"
                    }
                    if (evidence.length > FINAL_TASK_EVIDENCE_CAP) {
                        append("\n").append(softCapFinalText(evidence, FINAL_TASK_EVIDENCE_CAP))
                        append("\n(").append(evidence.length)
                            .append(" chars — full text in Activity log / timeline)")
                    } else {
                        append("\n").append(evidence)
                    }
                }
            }
        }

        /**
         * Footer: dry-run/apply badge + optional repoRoot + tool call count.
         * Badge strings keep [DRY_RUN_BADGE] / [APPLY_BADGE] content so existing
         * `.contains(…BADGE.trim())` checks remain valid.
         */
        fun formatFinalFooter(run: AgentClient.AgentRun): String {
            val badge = if (run.dryRun) DRY_RUN_BADGE else APPLY_BADGE
            val root = run.repoRoot.takeIf { it.isNotBlank() }?.let { "\n— repoRoot: $it" }.orEmpty()
            val tools = countFinalToolCalls(run)
            return badge + root + "\n— tools: $tools"
        }

        private fun noFinalAnswerPlaceholder(run: AgentClient.AgentRun): String =
            "(agent finished with no final answer — state=${run.state.ifBlank { "unknown" }})"

        /**
         * Structured final display: answer vs thinking split for the chat UI.
         *
         * - [answer]: operator-facing body (no plan/footer) — main bubble text
         * - [thinking]: mode + goal/plan (+ optional details) — collapsible panel
         * - [fullText]: combined markdown for history / copy / formatFinalContent
         *
         * Error / stop contracts set [answer] and [thinking] to null and put the
         * single-line message only in [fullText].
         */
        data class FinalDisplayParts(
            val fullText: String,
            val answer: String? = null,
            val thinking: String? = null,
        )

        /**
         * Pure terminal final content for a completed (or user-stopped) agent run.
         *
         * Hierarchy (success / userStopped-with-answer / empty-answer success):
         * **Answer** (condensed, soft-capped) → **Thinking** (header + goal/plan) →
         * optional Details (non-read evidence) → footer badges + repoRoot + tools.
         *
         * Failures keep leading `Error: …` (+ partial answer). Cancelled / user-stop
         * with empty answer stays exact [STOPPED_BY_USER].
         *
         * Always non-blank: empty [AgentClient.AgentRun.finalAnswer] never yields a blank
         * success bubble — uses a state-bearing placeholder under Answer instead.
         */
        fun formatFinalContent(run: AgentClient.AgentRun, userStopped: Boolean = false): String =
            formatFinalDisplayParts(run, userStopped).fullText

        /**
         * Same hierarchy as [formatFinalContent], but returns split parts so the UI can
         * render Answer as the bubble body and Thinking as a collapsible section.
         */
        fun formatFinalDisplayParts(run: AgentClient.AgentRun, userStopped: Boolean = false): FinalDisplayParts {
            val answer = run.finalAnswer?.trim().orEmpty()
            val err = run.error?.trim().orEmpty()
            // Error / pure-stop contracts — do not bury under a success hierarchy.
            when {
                userStopped && answer.isEmpty() ->
                    return FinalDisplayParts(fullText = STOPPED_BY_USER)
                err.isNotEmpty() && run.state == "failed" -> {
                    val text = ("Error: $err" + if (answer.isNotEmpty()) "\n\n$answer" else "")
                        .ifBlank { noFinalAnswerPlaceholder(run) }
                    return FinalDisplayParts(fullText = text)
                }
                answer.isEmpty() && (run.state == "cancelled" || userStopped) ->
                    return FinalDisplayParts(fullText = STOPPED_BY_USER)
                answer.isEmpty() && err.isNotEmpty() ->
                    return FinalDisplayParts(fullText = "Error: $err")
            }

            val rawPrimary = if (answer.isNotEmpty()) {
                extractPrimaryAnswer(answer)
            } else {
                noFinalAnswerPlaceholder(run)
            }
            val answerBody = condenseAnswerForDisplay(rawPrimary)
            val hasPrimary = answer.isNotEmpty() &&
                answerBody != noFinalAnswerPlaceholder(run) &&
                answerBody.length >= 20

            val thinkingCore = formatFinalThinkingSection(run)
            val evidence = formatFinalTaskEvidence(run, skipSuccessfulReads = hasPrimary)
            val thinkingBody = buildString {
                append(thinkingCore)
                if (evidence.isNotBlank()) {
                    append("\n\n")
                    append(evidence)
                }
            }
            val footer = formatFinalFooter(run)

            val fullText = buildString {
                append(formatFinalAnswerSection(answerBody))
                append("\n\n")
                append("### Thinking\n\n")
                append(thinkingBody)
                append(footer)
            }.ifBlank { noFinalAnswerPlaceholder(run) }

            // Display answer omits the "### Answer" wrapper for a cleaner bubble.
            val displayAnswer = softCapFinalText(answerBody, FINAL_ANSWER_SOFT_CAP)
            val displayThinking = thinkingBody.trim().ifBlank { null }

            return FinalDisplayParts(
                fullText = fullText,
                answer = displayAnswer,
                thinking = displayThinking,
            )
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
            val msg = run.message?.trim()?.takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
            return "Agent [$mode] ${run.state}$steps$msg"
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

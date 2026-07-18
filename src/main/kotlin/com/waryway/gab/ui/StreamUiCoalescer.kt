package com.waryway.gab.ui

/**
 * Batches stream deltas so the UI receives fewer, larger chunks.
 *
 * Pure (no Swing): callers schedule flushes on a timer and/or when
 * [offer] returns true (size threshold). Thread-safe for producer threads
 * offering deltas while an EDT timer drains.
 */
class StreamUiCoalescer(
    /** Recommend an immediate flush once pending reaches this many chars. */
    private val flushMaxChars: Int = DEFAULT_FLUSH_MAX_CHARS,
) {
    private val lock = Any()
    private val pending = StringBuilder()

    /**
     * Append a delta. Returns true if an immediate flush is recommended
     * because pending length crossed [flushMaxChars].
     */
    fun offer(delta: String): Boolean {
        if (delta.isEmpty()) return false
        synchronized(lock) {
            pending.append(delta)
            return pending.length >= flushMaxChars
        }
    }

    /** Drain and clear all pending text. Empty string if nothing pending. */
    fun drain(): String {
        synchronized(lock) {
            if (pending.isEmpty()) return ""
            val s = pending.toString()
            pending.setLength(0)
            return s
        }
    }

    /** Discard pending text without delivering it. */
    fun clear() {
        synchronized(lock) {
            pending.setLength(0)
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { pending.isEmpty() }

    fun pendingLength(): Int = synchronized(lock) { pending.length }

    companion object {
        const val DEFAULT_FLUSH_MAX_CHARS: Int = 4096
        /** Suggested EDT flush interval for high-frequency streams (~25 fps). */
        const val DEFAULT_FLUSH_INTERVAL_MS: Int = 40
    }
}

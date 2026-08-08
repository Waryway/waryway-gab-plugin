package com.waryway.gab.client

/**
 * Pure helper for merging streaming assistant content fragments.
 *
 * OpenAI-compatible APIs usually send **true deltas** (each chunk is only the new
 * characters). Some providers or proxies instead send **cumulative snapshots**
 * (each chunk is the full text so far). Blind [StringBuilder.append] of snapshots
 * produces quadratic growth (`H` + `He` + `Hel` → `HHeHel…`) and can exhaust RAM.
 *
 * Merge rules (see [merge]):
 * 1. empty incoming → keep existing
 * 2. empty existing → take incoming
 * 3. incoming starts with existing → snapshot extension: **replace** with incoming
 * 4. existing starts with shorter incoming → stale snapshot: **keep** existing
 * 5. otherwise → true delta: **append** incoming
 *
 * Safety: [merge] caps result length at [MAX_MERGED_CHARS] and appends
 * [TRUNCATION_MARKER] so a runaway stream cannot OOM the host process.
 * Pathological same-token spam on the true-delta path is only bounded by that cap
 * (legitimate long outputs are preserved up to the limit).
 */
internal object StreamContentMerger {

    /** Hard ceiling on accumulated stream content (characters). */
    const val MAX_MERGED_CHARS: Int = 1_000_000

    /** Appended when [MAX_MERGED_CHARS] would be exceeded. */
    const val TRUNCATION_MARKER: String =
        "\n\n[stream truncated: content exceeded safety limit]"

    /**
     * Merges [incoming] into [existing] using snapshot-vs-delta detection.
     * Result length never exceeds [MAX_MERGED_CHARS] + marker (marker fits in budget).
     */
    fun merge(existing: String, incoming: String): String {
        val merged = mergeUncapped(existing, incoming)
        if (merged.length <= MAX_MERGED_CHARS) return merged
        // Already truncated earlier — do not grow or re-append the marker.
        if (existing.contains(TRUNCATION_MARKER)) return existing
        val budget = (MAX_MERGED_CHARS - TRUNCATION_MARKER.length).coerceAtLeast(0)
        return merged.take(budget) + TRUNCATION_MARKER
    }

    /** Same rules as [merge] without the safety cap (for tests / composition). */
    fun mergeUncapped(existing: String, incoming: String): String {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming
        // Cumulative snapshot that extends (or equals) what we already have.
        if (incoming.startsWith(existing)) return incoming
        // Stale shorter snapshot of the same stream — ignore.
        if (existing.startsWith(incoming) && incoming.length < existing.length) return existing
        // True append delta.
        return existing + incoming
    }

    /**
     * Text that should be emitted to the UI for this chunk (new characters only).
     *
     * - Snapshot replace: only the suffix past [existing] (empty if no new chars)
     * - Stale shorter snapshot: empty
     * - True delta / first chunk: [incoming] as-is
     */
    fun visibleDelta(existing: String, incoming: String): String {
        if (incoming.isEmpty()) return ""
        if (existing.isEmpty()) return incoming
        if (incoming.startsWith(existing)) return incoming.substring(existing.length)
        if (existing.startsWith(incoming) && incoming.length < existing.length) return ""
        return incoming
    }
}

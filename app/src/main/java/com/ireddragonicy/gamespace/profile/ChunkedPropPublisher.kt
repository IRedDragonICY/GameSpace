package com.ireddragonicy.gamespace.profile

import com.ireddragonicy.gamespace.data.SettingsIO
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standard chunked sysprop publisher for large JSON payloads (thermal / charging
 * profile specs) that exceed the 92-byte system-property value cap.
 *
 * Write order is the torn-read guard the daemons rely on:
 *   1. payload chunks        (prefix.0 .. prefix.N-1)
 *   2. preCommitProps        (side channels: gpu/mon/sic, mode, ...)
 *   3. count                 (prefix.n)
 *   4. seq bump              (prefix.seq)  ← the "commit" the daemon waits for
 *   5. postCommitProps       (cosmetic labels, e.g. ss_profile)
 *
 * The daemon only re-reads the payload when `seq` changes, so everything the
 * payload depends on MUST be in place before step 4. The seq is bumped via a
 * read-modify-write of the property itself (NOT an in-memory counter) so a
 * process restart can never rewind it and silently skip the change — this fixes
 * a latent bug in ThermalController's old `++customSeq` field.
 */
@Singleton
class ChunkedPropPublisher @Inject constructor(
    private val io: SettingsIO,
) {
    /**
     * Publish [payload] as chunks. Returns false (and writes nothing) if the
     * payload would need more than [maxChunks] chunks.
     */
    fun publish(
        payload: String,
        chunkPrefix: String,
        countProp: String,
        seqProp: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        maxChunks: Int = DEFAULT_MAX_CHUNKS,
        preCommitProps: Map<String, String> = emptyMap(),
        postCommitProps: Map<String, String> = emptyMap(),
    ): Boolean {
        val chunks = payload.chunked(chunkSize)
        if (chunks.size > maxChunks) return false
        // 1. chunks, then blank any left over from a longer previous payload.
        //    The daemon only reads 0..n-1 so stale tails are harmless to it, but
        //    they accumulate in the property space and make `getprop` output
        //    actively misleading when debugging.
        chunks.forEachIndexed { i, chunk -> io.setProp("$chunkPrefix$i", chunk) }
        val previousCount = io.readPropLong(countProp, 0L).toInt()
        for (i in chunks.size until previousCount.coerceAtMost(maxChunks)) {
            io.setProp("$chunkPrefix$i", "")
        }
        // 2. side channels — written before the commit so the daemon never sees
        //    a half-updated set when it wakes on the seq bump
        preCommitProps.forEach { (k, v) -> io.setProp(k, v) }
        // 3. count
        io.setProp(countProp, chunks.size.toString())
        // 4. commit (read-modify-write, restart-safe)
        val nextSeq = io.readPropLong(seqProp, 0L) + 1L
        io.setProp(seqProp, nextSeq.toString())
        // 5. cosmetic labels
        postCommitProps.forEach { (k, v) -> io.setProp(k, v) }
        return true
    }

    /** Switch a mode selector without touching the chunked payload. */
    fun selectMode(modeProp: String, mode: String) {
        io.setProp(modeProp, mode)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 88
        const val DEFAULT_MAX_CHUNKS = 64
    }
}
package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture

import com.gmail.absolutebollockscreations.Fitbridge.logging.RotatingLogSink
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Timber tree that writes the capture channel into the `captures/` directory as
 * NDJSON (plan §0.9.2, §0.9.7).
 *
 * Routing is by tag alone: this tree accepts its [CaptureRole]'s reserved tag
 * and nothing else, while every human-log tree rejects all of them. Timber
 * already dispatches on `isLoggable`, so the reserved tag is the whole mechanism
 * and Timber itself needs no changes.
 *
 * **One tree per role, one file per role.** Both sides of the bridge produce the
 * same event shapes, so mixing them would make a capture misleading: `notify`
 * means "received from the machine" in the central file and "sent to the client"
 * in the peripheral one.
 *
 * Reuses [RotatingLogSink], which was written for the human log in 0.9.3 and
 * parameterised by extension and drop marker for exactly this second caller. The
 * capture channel therefore inherits the held-open stream, the bounded
 * non-blocking queue, the single writer thread, and size-capped rotation without
 * reimplementing any of it — and its overflow marker is the NDJSON `dropped`
 * event the §0.9.4 schema specifies, rather than the human-readable one.
 *
 * **Not gated by `DebugLevel`.** See [BleCapture].
 */
internal class CaptureTree(
    logsRoot: File,
    private val sessionId: String,
    private val headerProvider: () -> CaptureHeader,
    /**
     * Which side of the bridge this tree records. One tree per role, each
     * accepting only its own reserved tag and writing its own file, so a
     * `notify` is never ambiguous between "received from the machine" and "sent
     * to the watch".
     */
    private val role: CaptureRole = CaptureRole.CENTRAL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Timber.Tree() {

    private val captureRoot: File = File(logsRoot, CAPTURES_DIR_NAME).apply { mkdirs() }

    /**
     * Fixed at construction, **not** derived per line.
     *
     * [RotatingLogSink] re-evaluates the base name for every record — that is
     * what rolls the human log onto a new file at midnight. A capture wants the
     * opposite: one file per capture session, named for when the session began.
     * Deriving the stamp from each record's timestamp would start a new file
     * every second.
     */
    private val baseName: String = String.format(
        Locale.US,
        "capture-%s-%s-%s",
        STAMP_FORMAT.format(Instant.ofEpochMilli(nowMs()).atZone(ZONE)),
        sessionId,
        role.fileSuffix,
    )

    private val sink = RotatingLogSink(
        directory = captureRoot,
        baseNameFor = { baseName },
        extension = NDJSON_EXTENSION,
        // The schema's own overflow event, so a dropped line is still valid
        // NDJSON and a parser does not have to special-case it.
        dropMarker = { dropped -> CaptureJson.encode(droppedRecord(dropped)) },
    )

    @Volatile
    private var headerWritten = false

    private val lock = Any()

    init {
        installed[role] = this
    }

    // Both widened from Timber's `protected`. Routing is the whole point of this
    // class and getting it wrong is silent in both directions, so it is worth
    // being able to assert on directly rather than only through a planted tree
    // and global Timber state.
    public override fun isLoggable(tag: String?, priority: Int): Boolean = tag == role.tag

    public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Defensive: Timber consults isLoggable, but a caller holding this tree
        // directly must not be able to put a human line - or the other role's
        // events - into this file.
        if (tag != role.tag) return
        writeHeaderOnce()
        sink.write(message)
    }

    /**
     * The header is written lazily on the first event rather than at
     * construction, so arming a capture that never records anything does not
     * leave an orphan file behind.
     */
    private fun writeHeaderOnce() {
        if (headerWritten) return
        synchronized(lock) {
            if (headerWritten) return
            headerWritten = true
            runCatching { sink.write(CaptureJson.header(headerProvider())) }
        }
    }

    /** Directory holding capture files, for the exporter. */
    fun capturesDirectory(): File = captureRoot

    /** Blocks until buffered events reach disk. Called before export. */
    fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean = sink.flush(timeoutMs)

    fun close() = sink.close()

    private fun droppedRecord(dropped: Int): CaptureRecord = CaptureRecord(
        atMs = nowMs(),
        // Sequence zero marks a synthetic record: it was generated by the writer
        // rather than by anything that happened on the air.
        seq = 0L,
        event = DroppedEvent(count = dropped),
        role = role,
    )

    companion object {
        const val CAPTURES_DIR_NAME: String = "captures"
        const val NDJSON_EXTENSION: String = "ndjson"
        const val DEFAULT_FLUSH_TIMEOUT_MS: Long = 2_000L

        /**
         * Planted trees by role, so the exporter can flush before zipping without
         * threading references through the Application.
         */
        private val installed = ConcurrentHashMap<CaptureRole, CaptureTree>()

        fun current(role: CaptureRole): CaptureTree? = installed[role]

        /** Every planted tree, so the exporter flushes both roles before zipping. */
        fun all(): Collection<CaptureTree> = installed.values

        private val ZONE: ZoneId = ZoneId.systemDefault()

        /** `capture-<yyyyMMdd_HHmmss>-<session>-<role>.ndjson`. */
        private val STAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    }
}

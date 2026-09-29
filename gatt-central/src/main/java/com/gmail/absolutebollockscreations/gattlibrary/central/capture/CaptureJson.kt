package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
/**
 * Hand-rolled NDJSON emitter for [CaptureEvent] (plan §0.9.4).
 *
 * NDJSON because it is append-only and therefore survives a crash mid-session —
 * which is exactly when the capture matters — and because it is streamable,
 * greppable, and converts to a §5 fixture by filtering lines.
 *
 * Hand-rolled rather than reflective because the schema is small and fixed: a
 * serialization library would add a dependency and per-event cost on a path that
 * runs once per BLE notification, for nothing. The hex table is precomputed for
 * the same reason.
 *
 * **Field order is fixed and part of the contract.** `t`, `seq`, `conn`, `ev`,
 * then the event's own fields. Tests assert exact lines, so an accidental
 * reordering is caught rather than silently changing every capture ever taken.
 *
 * Every method returns a single line with no trailing newline; the sink owns
 * line termination.
 */
@GattLibraryInternalApi
object CaptureJson {

    private val HEX = "0123456789abcdef".toCharArray()

    /** First line of a capture file. */
    fun header(header: CaptureHeader): String = buildString(128) {
        append('{')
        appendNumberField("capture_version", header.captureVersion.toLong(), first = true)
        appendStringField("app", header.appVersion)
        appendNumberField("android", header.androidSdk.toLong())
        appendStringField("model", header.deviceModel)
        appendStringField("session", header.session)
        appendNumberField("t0", header.t0Ms)
        append('}')
    }

    fun encode(record: CaptureRecord): String = buildString(160) {
        append('{')
        appendNumberField("t", record.atMs, first = true)
        appendNumberField("seq", record.seq)
        record.event.conn?.let { appendStringField("conn", it) }
        appendStringField("ev", record.event.ev)
        appendEventBody(record.event)
        append('}')
    }

    private fun StringBuilder.appendEventBody(event: CaptureEvent) {
        when (event) {
            is ScanEvent -> {
                append(",\"name\":")
                if (event.name == null) append("null") else appendJsonString(event.name)
                appendNumberField("rssi", event.rssi.toLong())
                append(",\"services\":")
                appendStringArray(event.serviceUuids)
                append(",\"mfg\":{")
                var first = true
                for ((company, payload) in event.manufacturerData) {
                    if (!first) append(',')
                    first = false
                    appendJsonString(company.toString())
                    append(':')
                    appendHexString(payload)
                }
                append("},\"svcdata\":{")
                first = true
                for ((uuid, payload) in event.serviceData) {
                    if (!first) append(',')
                    first = false
                    appendJsonString(uuid)
                    append(':')
                    appendHexString(payload)
                }
                append('}')
            }

            is DroppedEvent -> appendNumberField("n", event.count.toLong())

            is ConnectEvent -> {
                appendNumberField("gen", event.generation.toLong())
                // Omitted rather than written as null when unknown, so a central
                // connect event keeps the line it has always had.
                event.mtu?.let { appendNumberField("mtu", it.toLong()) }
            }

            is DisconnectEvent -> {
                appendStringField("reason", event.reason)
                appendNumberField("gen", event.generation.toLong())
            }

            is GattInventoryEvent -> {
                append(",\"services\":[")
                event.services.forEachIndexed { index, service ->
                    if (index > 0) append(',')
                    append('{')
                    appendStringField("uuid", service.uuid, first = true)
                    append(",\"chars\":[")
                    service.characteristics.forEachIndexed { charIndex, characteristic ->
                        if (charIndex > 0) append(',')
                        append('{')
                        appendStringField("uuid", characteristic.uuid, first = true)
                        appendNumberField("props", characteristic.properties.toLong())
                        append(",\"descriptors\":")
                        appendStringArray(characteristic.descriptors)
                        append('}')
                    }
                    append("]}")
                }
                append(']')
            }

            is CccdEvent -> {
                appendStringField("char", event.characteristic)
                appendBooleanField("enabled", event.enabled)
                appendStringField("result", event.result)
            }

            is NotifyEvent -> appendPayload(event.characteristic, event.payload)

            is ReadEvent -> appendPayload(event.characteristic, event.payload)

            is WriteEvent -> {
                appendPayload(event.characteristic, event.payload)
                appendBooleanField("withoutResponse", event.withoutResponse)
            }

            is RedactedWriteEvent -> {
                appendStringField("char", event.characteristic)
                // No hex field exists on this type, so none can be written.
                appendStringField("redacted", event.reason.wireName)
                appendNumberField("len", event.length.toLong())
                appendBooleanField("withoutResponse", event.withoutResponse)
            }

            is MatchEvent -> {
                append(",\"candidates\":[")
                event.candidates.forEachIndexed { index, candidate ->
                    if (index > 0) append(',')
                    append('{')
                    appendStringField("adapter", candidate.adapterId, first = true)
                    appendNumberField("score", candidate.score.toLong())
                    appendStringField("evidence", candidate.evidence)
                    append('}')
                }
                append("],\"selected\":")
                if (event.selected == null) append("null") else appendJsonString(event.selected)
            }

            is DemoteEvent -> {
                appendStringField("from", event.from)
                append(",\"to\":")
                if (event.to == null) append("null") else appendJsonString(event.to)
                appendStringField("reason", event.reason)
                appendNumberField("timeoutMs", event.timeoutMs)
                appendStringField("evidence", event.evidence)
            }

            is SampleEvent -> {
                appendStringField("sourceId", event.sourceId)
                appendStringField("metric", event.metric)
                append(",\"value\":")
                appendNumber(event.value)
                appendStringField("unit", event.unit)
                appendStringField("quality", event.quality)
            }

            is AdvertisementSourceEvent -> {
                appendStringField("family", event.family)
                appendStringField("candidate", event.candidate)
                append(",\"systemId\":")
                if (event.systemId == null) append("null") else append(event.systemId)
                append(",\"firmware\":")
                if (event.firmware == null) append("null") else appendJsonString(event.firmware)
                appendNumberField("generation", event.generation)
                appendStringField("state", event.state)
                appendStringField("decision", event.decision)
                append(",\"freshnessMs\":")
                if (event.freshnessMs == null) append("null") else append(event.freshnessMs)
            }

            is ErrorEvent -> {
                appendStringField("where", event.where)
                appendStringField("message", event.message)
            }
        }
    }

    private fun StringBuilder.appendPayload(characteristic: String, payload: ByteArray) {
        appendStringField("char", characteristic)
        append(",\"hex\":")
        appendHexString(payload)
        appendNumberField("len", payload.size.toLong())
    }

    private fun StringBuilder.appendStringField(name: String, value: String, first: Boolean = false) {
        if (!first) append(',')
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendNumberField(name: String, value: Long, first: Boolean = false) {
        if (!first) append(',')
        appendJsonString(name)
        append(':')
        append(value)
    }

    private fun StringBuilder.appendBooleanField(name: String, value: Boolean) {
        append(',')
        appendJsonString(name)
        append(':')
        append(if (value) "true" else "false")
    }

    private fun StringBuilder.appendStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendJsonString(value)
        }
        append(']')
    }

    /**
     * JSON has no NaN or Infinity. A non-finite metric is written as null rather
     * than as a token that would make the whole line unparseable.
     */
    private fun StringBuilder.appendNumber(value: Double) {
        if (value.isFinite()) append(value) else append("null")
    }

    /** Lowercase hex, no separators, quoted. */
    private fun StringBuilder.appendHexString(bytes: ByteArray) {
        append('"')
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
        append('"')
    }

    /**
     * Minimal RFC 8259 string escaping. Device names arrive from the air and are
     * untrusted: a name containing a quote, a backslash, or a control character
     * would otherwise corrupt the line and every tool reading it.
     */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '\b' -> append("\\b")
                // Kotlin has no \f char literal, so form feed is left to the generic
                // control-character branch below, which emits \u000c.
                ch < ' ' -> {
                    append("\\u00")
                    val code = ch.code
                    append(HEX[code ushr 4])
                    append(HEX[code and 0x0F])
                }

                else -> append(ch)
            }
        }
        append('"')
    }
}

package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

@FitBridgeBleInternalApi
data class RideOnTelemetry(
    val powerWatts: Int?,
    val cadenceRpm: Int?,
    val speedKph: Double?,
    val heartRateBpm: Int?,
    val diagnosticFields: Map<Int, Long>,
)

@FitBridgeBleInternalApi
fun decodeRideOnTelemetry(packet: ByteArray): RideOnTelemetry? {
    val decoded = (RideOnProtoCodec.decode(packet) as? RideOnDecodeResult.Decoded)?.packet ?: return null
    if (decoded.opcode != RideOnUuids.OPCODE_RIDING_DATA) return null
    val known = decoded.fields.filter { it.number in 1..6 }
    if (known.any { it !is RideOnProtoField.Varint }) return null
    if (known.groupBy { it.number }.any { it.value.size != 1 }) return null
    fun value(field: Int): Long? = (known.firstOrNull { it.number == field } as? RideOnProtoField.Varint)?.value
    fun bounded(field: Int, range: LongRange): Int? = value(field)?.takeIf { it in range }?.toInt()
    return RideOnTelemetry(
        powerWatts = bounded(1, 0L..3_000L),
        cadenceRpm = bounded(2, 0L..300L),
        speedKph = value(3)?.takeIf { it in 0L..20_000L }?.div(100.0),
        heartRateBpm = bounded(4, 1L..254L),
        diagnosticFields = (5..6).mapNotNull { field -> value(field)?.let { field to it } }.toMap(),
    )
}

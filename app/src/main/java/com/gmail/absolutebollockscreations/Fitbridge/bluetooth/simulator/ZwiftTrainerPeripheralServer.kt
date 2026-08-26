package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RIDEON_GEAR_RATIOS
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnDecodeResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnDecodedPacket
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnProtoCodec
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnProtoField
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnServiceForm
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnUuids
import kotlin.math.roundToLong

internal enum class ZwiftTrainerStage { CONNECTED, NEGOTIATED, INITIALIZED, READY }

internal data class ZwiftTrainerServerFrame(
    val async: Boolean,
    val value: ByteArray,
)

internal data class ZwiftTrainerServerOutcome(
    val accepted: Boolean,
    val negotiated: Boolean = false,
    val frames: List<ZwiftTrainerServerFrame> = emptyList(),
    val reason: String? = null,
)

/** Per-link peripheral inverse of the production Zwift trainer-control session. */
internal class ZwiftTrainerPeripheralServer(
    private val form: RideOnServiceForm,
    private val model: KickrSyntheticModel,
) {
    var stage = ZwiftTrainerStage.CONNECTED
        private set

    val controlReady: Boolean get() = stage == ZwiftTrainerStage.READY

    fun handle(value: ByteArray, bothTxSubscribed: Boolean): ZwiftTrainerServerOutcome {
        if (!bothTxSubscribed) return rejected("both TX CCCDs are required")
        val handshake = when (form) {
            RideOnServiceForm.ORIGINAL -> RideOnUuids.HANDSHAKE_ORIGINAL
            RideOnServiceForm.CORE2 -> RideOnUuids.HANDSHAKE_CORE2
        }
        if (value.contentEquals(handshake)) {
            if (stage != ZwiftTrainerStage.CONNECTED) return rejected("duplicate handshake")
            stage = ZwiftTrainerStage.NEGOTIATED
            return ZwiftTrainerServerOutcome(
                accepted = true,
                negotiated = true,
                frames = listOf(ZwiftTrainerServerFrame(async = false, handshake.copyOf())),
            )
        }
        if (stage == ZwiftTrainerStage.CONNECTED) return rejected("handshake required")
        if (value.contentEquals(INITIALIZATION)) {
            if (stage != ZwiftTrainerStage.NEGOTIATED) return rejected("initialization out of order")
            stage = ZwiftTrainerStage.INITIALIZED
            return ZwiftTrainerServerOutcome(true)
        }
        if (value.contentEquals(GENERAL_INFORMATION_REQUEST)) {
            if (stage != ZwiftTrainerStage.INITIALIZED) return rejected("general-information request out of order")
            stage = ZwiftTrainerStage.READY
            return ZwiftTrainerServerOutcome(true, frames = listOf(telemetryFrame()))
        }
        if (!controlReady) return rejected("initialization incomplete")
        val packet = (RideOnProtoCodec.decode(value) as? RideOnDecodeResult.Decoded)?.packet
            ?: return rejected("malformed protobuf packet")
        return when (packet.opcode) {
            RideOnUuids.OPCODE_HUB_COMMAND -> handleHubCommand(packet)
            RideOnUuids.OPCODE_VIRTUAL_SHIFT -> handleVirtualShift(packet)
            else -> rejected("unsupported opcode 0x${packet.opcode.toString(16)}")
        }
    }

    fun telemetryFrame(): ZwiftTrainerServerFrame {
        val snapshot = model.snapshot()
        return ZwiftTrainerServerFrame(
            async = true,
            value = RideOnProtoCodec.ridingData(
                snapshot.powerWatts.coerceIn(0, 3_000).toLong(),
                snapshot.cadenceRpm.roundToLong().coerceIn(0, 300),
                (snapshot.speedKph * 100.0).roundToLong().coerceIn(0, 20_000),
                snapshot.heartRateBpm.coerceIn(1, 254).toLong(),
            ),
        )
    }

    fun reset() {
        stage = ZwiftTrainerStage.CONNECTED
    }

    private fun handleHubCommand(packet: RideOnDecodedPacket): ZwiftTrainerServerOutcome {
        val known = packet.fields.filter { it.number in 1..5 }
        if (known.groupBy { it.number }.any { it.value.size > 1 }) return rejected("duplicate HubCommand field")
        if (known.any { field ->
                when (field.number) {
                    3 -> field !is RideOnProtoField.Varint
                    4, 5 -> field !is RideOnProtoField.Bytes
                    else -> false
                }
            }
        ) return rejected("wrong HubCommand wire type")
        if (known.any { it.number == 1 }) return rejected("direct resistance is unsupported")
        val power = known.singleOrNull { it.number == 3 } as? RideOnProtoField.Varint
        val simulation = known.singleOrNull { it.number == 4 } as? RideOnProtoField.Bytes
        val physical = known.singleOrNull { it.number == 5 } as? RideOnProtoField.Bytes
        if (power != null && simulation != null) return rejected("mixed ERG and simulation command")
        if (power != null) {
            if (power.value !in 0L..2_000L ||
                !model.setTargetPower(power.value.toInt(), KickrControlSource.RIDE_ON)
            ) {
                return rejected("target power out of range")
            }
            return ZwiftTrainerServerOutcome(true)
        }
        if (simulation == null || physical == null) return rejected("incomplete simulation command")
        val sim = nested(simulation.value) ?: return rejected("invalid SimulationParam")
        val phy = nested(physical.value) ?: return rejected("invalid PhysicalParam")
        val simValues = exactVarints(sim, setOf(1, 2, 3, 4)) ?: return rejected("invalid SimulationParam fields")
        val physicalValues = optionalVarints(phy, required = setOf(4, 5), optional = setOf(2))
            ?: return rejected("invalid PhysicalParam fields")
        val target = runCatching {
            KickrSimulationTarget(
                windMps = RideOnProtoCodec.zigZagDecode32(simValues.getValue(1)) / 100.0,
                gradePercent = RideOnProtoCodec.zigZagDecode32(simValues.getValue(2)) / 100.0,
                cw = simValues.getValue(3) / 10_000.0,
                crr = simValues.getValue(4) / 100_000.0,
            )
        }.getOrNull() ?: return rejected("invalid signed simulation value")
        val gear = physicalValues[2]?.let { rawRatio ->
            val index = RIDEON_GEAR_RATIOS.indexOf(rawRatio.toInt()) + 1
            if (index <= 0) return rejected("gear ratio is not in the 24-gear table")
            index to (rawRatio / 10_000.0)
        }
        if (!model.setSimulation(target, KickrControlSource.RIDE_ON)) {
            return rejected("simulation target out of range")
        }
        // A gear ratio riding along with a simulation command stays part of that simulation
        // write (see KickrSyntheticModel.markDirectControl's KDoc) - it must not flip the
        // control-mode label to RideOn-Direct.
        gear?.let { (index, ratio) -> check(model.setVirtualGear(index, ratio)) }
        return ZwiftTrainerServerOutcome(true)
    }

    private fun handleVirtualShift(packet: RideOnDecodedPacket): ZwiftTrainerServerOutcome {
        val values = optionalVarints(packet, required = setOf(1, 2), optional = emptySet(), bytes = setOf(4))
            ?: return rejected("invalid VirtualShiftCommand fields")
        if (values.getValue(1) != 1L) return rejected("unsupported virtual-shift command type")
        val payload = packet.fields.single { it.number == 4 } as RideOnProtoField.Bytes
        val nested = nested(payload.value) ?: return rejected("invalid VirtualShiftPayload")
        val payloadValues = exactVarints(nested, setOf(7)) ?: return rejected("invalid gear-ratio payload")
        val rawRatio = payloadValues.getValue(7)
        val index = RIDEON_GEAR_RATIOS.indexOf(rawRatio.toInt()) + 1
        if (index <= 0 || !model.setVirtualGear(index, rawRatio / 10_000.0)) {
            return rejected("gear ratio is not in the 24-gear table")
        }
        // A bare virtual-shift command - no power or simulation target alongside it - is the
        // "gear shift" half of RideOn-Direct (plan: simulator control-mode display).
        model.markDirectControl(KickrControlSource.RIDE_ON)
        return ZwiftTrainerServerOutcome(
            true,
            frames = listOf(
                ZwiftTrainerServerFrame(
                    async = false,
                    value = RideOnProtoCodec.virtualShiftResponse(values.getValue(2)),
                ),
            ),
        )
    }

    private fun nested(bytes: ByteArray): RideOnDecodedPacket? =
        ((RideOnProtoCodec.decode(byteArrayOf(0x00) + bytes) as? RideOnDecodeResult.Decoded)?.packet)

    private fun exactVarints(packet: RideOnDecodedPacket, fields: Set<Int>): Map<Int, Long>? =
        optionalVarints(packet, required = fields, optional = emptySet())

    private fun optionalVarints(
        packet: RideOnDecodedPacket,
        required: Set<Int>,
        optional: Set<Int>,
        bytes: Set<Int> = emptySet(),
    ): Map<Int, Long>? {
        val known = packet.fields.filter { it.number in required || it.number in optional || it.number in bytes }
        if (known.groupBy { it.number }.any { it.value.size != 1 }) return null
        if (!required.all { number -> known.any { it.number == number } }) return null
        if (!bytes.all { number -> known.singleOrNull { it.number == number } is RideOnProtoField.Bytes }) return null
        if (known.any { it.number !in bytes && it !is RideOnProtoField.Varint }) return null
        return known.filterIsInstance<RideOnProtoField.Varint>().associate { it.number to it.value }
    }

    private fun rejected(reason: String) = ZwiftTrainerServerOutcome(false, reason = reason)

    private companion object {
        val INITIALIZATION = byteArrayOf(0x41, 0x08, 0x05)
        val GENERAL_INFORMATION_REQUEST = byteArrayOf(0x00, 0x08, 0x00)
    }
}

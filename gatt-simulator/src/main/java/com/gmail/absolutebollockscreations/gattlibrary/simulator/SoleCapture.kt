package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.buildFtmsFeaturePayload
import com.gmail.absolutebollockscreations.gattlibrary.server.measurementCapabilities
import java.io.Reader
import java.util.UUID

/** Selects a capture-derived Sole GATT surface and workout packet format. */
public enum class SoleSimulatorProfile(
    public val displayName: String,
    public val advertisedName: String,
) {
    ST90("Sole ST90", "ST90"),
    F80_MODERN("Sole F80 (modern FTMS)", "F80"),
    F65("Sole F65 (short FTMS)", "F65"),
    F63("Sole F63", "F63"),
    F80_LEGACY("Sole F80 (legacy)", "F80"),
    F85_58("Sole F85 (legacy, 58 characteristics)", "F85"),
    F85_59("Sole F85 (legacy, 59 characteristics)", "F85"),
    LCB("Sole LCB", "LCB"),
    R92("Sole R92", "R92"),
}

internal data class SoleAttribute(val service: UUID, val characteristic: UUID)

internal data class SoleCharacteristic(
    val key: SoleAttribute,
    val properties: Int,
    val hasCccd: Boolean,
    val readValue: ByteArray?,
)

internal data class SoleReplayEvent(
    val delayMs: Long,
    val operation: SoleReplayOperation,
    val key: SoleAttribute,
    val value: ByteArray,
)

internal enum class SoleReplayOperation { NOTIFY, INDICATE, WRITE_REQUEST, WRITE_COMMAND }

internal enum class SoleReplayLane { TREADMILL, VENDOR, CONTROL }

internal data class SoleCapture(
    val advertisedServices: List<UUID>,
    val ftmsServiceData: ByteArray?,
    val services: Map<UUID, List<SoleCharacteristic>>,
    val lanes: Map<SoleReplayLane, List<SoleReplayEvent>>,
) {
    fun forSimulation(profile: SoleSimulatorProfile, targets: TargetCapabilities = TargetCapabilities.NONE): SoleCapture {
        val effectiveTargets = targets.takeIf { profile.testControlTargets.any } ?: TargetCapabilities.NONE
        val ftms = services.getValue(treadmill.service)
        val features = buildFtmsFeaturePayload(
            profile.machine, effectiveTargets,
            measurementCapabilities = profile.machine.measurementCapabilities.copy(elevationGain = false),
        )
        val readOverrides = buildMap {
            if (profile != SoleSimulatorProfile.F65) put(FtmsUuids.UUID_FITNESS_MACHINE_FEATURE, features)
            if (effectiveTargets.supportsSpeed) {
                put(FtmsUuids.UUID_SUPPORTED_SPEED_RANGE, requireNotNull(effectiveTargets.speedRange).encodeU16())
            }
            if (effectiveTargets.supportsInclination) {
                put(FtmsUuids.UUID_SUPPORTED_INCLINATION_RANGE, requireNotNull(effectiveTargets.inclinationRange).encodeS16())
            }
        }
        val simulatedFtms = ftms.filter { characteristic ->
            val uuid = characteristic.key.characteristic
            when (uuid) {
                FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT -> effectiveTargets.any
                in targetRangeUuids -> uuid in readOverrides
                else -> true
            }
        }.map { characteristic ->
            readOverrides[characteristic.key.characteristic]?.let { characteristic.copy(readValue = it) }
                ?: characteristic
        } + readOverrides.filterKeys { uuid -> ftms.none { it.key.characteristic == uuid } }.map { (uuid, value) ->
            SoleCharacteristic(SoleAttribute(treadmill.service, uuid), properties = 0x02, hasCccd = false, readValue = value)
        }
        val simulationServices = services + (treadmill.service to simulatedFtms)
        if (!profile.usesProprietaryWorkout) {
            return copy(services = simulationServices, lanes = emptyMap())
        }
        val vendor = lanes.getValue(SoleReplayLane.VENDOR)
        // R92's captured workout frame ends in a separate two-byte notification.
        var continuation = false
        val setup = vendor.filter { event ->
            if (event.operation != SoleReplayOperation.NOTIFY || event.key != vendorNotify) return@filter true
            val value = event.value
            if (continuation && value.size == 2 && value.last() == 0x5d.toByte()) {
                continuation = false
                return@filter false
            }
            continuation = false
            val workout = (value.size == 18 || value.size == 20 || value.size == 22) &&
                value[0] == 0x5b.toByte() && value[2] == 0x06.toByte()
            continuation = workout && profile == SoleSimulatorProfile.R92 && value.size == 20
            // Resistance is generated with each bike sample. Keep any initial idle announcement
            // before the first write, since the R92 capture starts with one.
            val resistance = value.size == 5 && value[0] == 0x5b.toByte() && value[2] == 0x13.toByte()
            !workout && (!resistance || event === vendor.first())
        }
        return copy(
            services = simulationServices,
            lanes = mapOf(SoleReplayLane.VENDOR to setup),
        )
    }

    companion object {
        val targetRangeUuids = setOf(
            FtmsUuids.UUID_SUPPORTED_SPEED_RANGE, FtmsUuids.UUID_SUPPORTED_INCLINATION_RANGE,
            FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE, FtmsUuids.UUID_SUPPORTED_HEART_RATE_RANGE,
            FtmsUuids.UUID_SUPPORTED_POWER_RANGE,
        )
        val treadmill = SoleAttribute(soleUuid("1826"), soleUuid("2acd"))
        val controlPoint = SoleAttribute(soleUuid("1826"), soleUuid("2ad9"))
        val vendorNotify = SoleAttribute(
            soleUuid("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
            soleUuid("49535343-1e4d-4bd9-ba61-23c647249616"),
        )
        val vendorWrite = SoleAttribute(
            soleUuid("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
            soleUuid("49535343-8841-43f4-a8d4-ecbe34729bb3"),
        )

        fun load(reader: Reader): SoleCapture = reader.buffered().useLines { lines ->
            var advertised = emptyList<UUID>()
            var ftmsServiceData: ByteArray? = null
            val services = linkedMapOf<UUID, MutableList<SoleCharacteristic>>()
            val lanes = linkedMapOf<SoleReplayLane, MutableList<SoleReplayEvent>>()
            var service: UUID? = null
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split(' ')
                when (parts[0]) {
                    "A" -> advertised = parts.drop(1).map(::soleUuid)
                    "D" -> ftmsServiceData = parts[1].hexToByteArray()
                    "S" -> {
                        val uuid = soleUuid(parts[1])
                        check(services.put(uuid, mutableListOf()) == null)
                        service = uuid
                    }
                    "G" -> {
                        val uuid = checkNotNull(service)
                        services.getValue(uuid).add(
                            SoleCharacteristic(
                                SoleAttribute(uuid, soleUuid(parts[1])),
                                parts[2].toInt(16),
                                parts[3] == "1",
                                parts[4].takeUnless { it == "-" }?.hexToByteArray(),
                            ),
                        )
                    }
                    "T", "V", "C" -> {
                        val lane = when (parts[0]) {
                            "T" -> SoleReplayLane.TREADMILL
                            "V" -> SoleReplayLane.VENDOR
                            else -> SoleReplayLane.CONTROL
                        }
                        val operation = when (parts[2]) {
                            "N" -> SoleReplayOperation.NOTIFY
                            "I" -> SoleReplayOperation.INDICATE
                            "W" -> SoleReplayOperation.WRITE_REQUEST
                            "U" -> SoleReplayOperation.WRITE_COMMAND
                            else -> error("Unknown Sole replay operation")
                        }
                        lanes.getOrPut(lane) { mutableListOf() }.add(
                            SoleReplayEvent(
                                parts[1].toLong(),
                                operation,
                                SoleAttribute(soleUuid(parts[3]), soleUuid(parts[4])),
                                parts[5].takeUnless { it == "-" }?.hexToByteArray() ?: ByteArray(0),
                            ),
                        )
                    }
                    else -> error("Unknown Sole fixture record")
                }
            }
            val attributes = services.values.flatten().associateBy { it.key }
            check(advertised.isNotEmpty() && advertised.all { it in services })
            check(attributes.size == services.values.sumOf { it.size })
            lanes.values.flatten().forEach { event ->
                val character = attributes.getValue(event.key)
                val property = when (event.operation) {
                    SoleReplayOperation.NOTIFY -> 0x10
                    SoleReplayOperation.INDICATE -> 0x20
                    SoleReplayOperation.WRITE_REQUEST -> 0x08
                    SoleReplayOperation.WRITE_COMMAND -> 0x04
                }
                check(event.delayMs >= 0 && character.properties and property != 0)
            }
            SoleCapture(advertised, ftmsServiceData, services, lanes)
        }
    }
}

internal fun soleUuid(value: String): UUID =
    UUID.fromString(if (value.length == 4) "0000$value-0000-1000-8000-00805f9b34fb" else value)

/** A strict transcript cursor. Input frames are matched in order, with their captured write mode. */
internal class SoleReplayCursor(private val events: List<SoleReplayEvent>) {
    private var index = 0
    private var acceptedWrite: Int? = null

    fun takeOutput(): SoleReplayEvent? {
        if (acceptedWrite == index) {
            acceptedWrite = null
            index++
        }
        val event = events.getOrNull(index) ?: return null
        return when (event.operation) {
            SoleReplayOperation.NOTIFY, SoleReplayOperation.INDICATE -> event.also { index++ }
            else -> null
        }
    }

    fun accept(key: SoleAttribute, responseNeeded: Boolean, value: ByteArray): Boolean {
        if (acceptedWrite != null) return false
        // A test client can write sooner than the source client did. Allow the next captured
        // write while preceding asynchronous outputs are still being delivered.
        var expected = index
        while (expected < events.size && (events[expected].operation == SoleReplayOperation.NOTIFY ||
                events[expected].operation == SoleReplayOperation.INDICATE)
        ) {
            expected++
        }
        val event = events.getOrNull(expected) ?: return false
        val operation = if (responseNeeded) SoleReplayOperation.WRITE_REQUEST else SoleReplayOperation.WRITE_COMMAND
        if (event.operation != operation || event.key != key || !event.value.contentEquals(value)) return false
        if (expected == index) index++ else acceptedWrite = expected
        return true
    }
}

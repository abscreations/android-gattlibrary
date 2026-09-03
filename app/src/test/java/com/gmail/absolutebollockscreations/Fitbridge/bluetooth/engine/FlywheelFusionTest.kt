package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class FlywheelFusionTest {
    @Test
    fun `native cycling sensors coexist and outrank Flywheel estimates`() {
        var now = 100L
        val state = BtEngineState()
        val fusion = MetricFusion(state, MonotonicMetricClock { now })
        fusion.startSession(FusionPersona.RIDE, 0L)
        val flywheelConnection = ConnectionId("flywheel", 1L)
        fusion.selectMachine(flywheelConnection)
        val flywheelPower = source(flywheelConnection, MetricSourceClass.VENDOR_NATIVE)
        val flywheelSpeed = source(flywheelConnection, MetricSourceClass.DERIVED)
        val sensorConnection = ConnectionId("cps", 1L)
        val cpsPower = source(sensorConnection, MetricSourceClass.CPS)
        val wheelSpeed = source(sensorConnection, MetricSourceClass.CPS_WHEEL)

        emit(fusion, flywheelPower, MetricKind.POWER, 200.0, now)
        emit(fusion, flywheelSpeed, MetricKind.SPEED, 30.0, now, MetricQuality.ESTIMATED)
        emit(fusion, cpsPower, MetricKind.POWER, 220.0, ++now)
        emit(fusion, wheelSpeed, MetricKind.SPEED, 32.0, now, MetricQuality.DERIVED)

        assertEquals(cpsPower, fusion.selectedSource(MetricKind.POWER))
        assertEquals(wheelSpeed, fusion.selectedSource(MetricKind.SPEED))
        fusion.assign(MetricKind.POWER, flywheelPower, now)
        assertEquals(flywheelPower, fusion.selectedSource(MetricKind.POWER))
        fusion.clearAssignment(MetricKind.POWER, now)
        assertEquals(cpsPower, fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `stale Flywheel speed expires without publishing zero`() {
        var now = 100L
        val state = BtEngineState()
        val fusion = MetricFusion(state, MonotonicMetricClock { now })
        fusion.startSession(FusionPersona.RIDE, 0L)
        val connection = ConnectionId("flywheel", 1L)
        fusion.selectMachine(connection)
        val speed = source(connection, MetricSourceClass.DERIVED)
        emit(fusion, speed, MetricKind.SPEED, 28.0, now, MetricQuality.ESTIMATED)
        assertEquals(28.0, state.speedKph.value!!, 0.0)

        now += 3_001L
        fusion.refresh(now)

        assertNull(state.speedKph.value)
        assertNull(fusion.latestSample(MetricKind.SPEED))
    }

    private fun source(connection: ConnectionId, sourceClass: MetricSourceClass) = MetricSourceId(
        MetricOriginId.Gatt(connection),
        MetricProtocol.VENDOR,
        MetricEndpointId.GattCharacteristic(UUID.nameUUIDFromBytes(sourceClass.name.toByteArray())),
    )

    private fun emit(
        fusion: MetricFusion,
        source: MetricSourceId,
        metric: MetricKind,
        value: Double,
        arrivalMs: Long,
        quality: MetricQuality = MetricQuality.NATIVE,
    ) {
        val sourceClass = when (source.gattCharacteristicUuid) {
            UUID.nameUUIDFromBytes(MetricSourceClass.CPS.name.toByteArray()) -> MetricSourceClass.CPS
            UUID.nameUUIDFromBytes(MetricSourceClass.CPS_WHEEL.name.toByteArray()) -> MetricSourceClass.CPS_WHEEL
            UUID.nameUUIDFromBytes(MetricSourceClass.DERIVED.name.toByteArray()) -> MetricSourceClass.DERIVED
            else -> MetricSourceClass.VENDOR_NATIVE
        }
        fusion.submit(
            MetricSample(
                source.originId,
                source,
                source.protocol,
                sourceClass,
                metric,
                arrivalMs,
                value = value,
                quality = quality,
            ),
        )
    }
}

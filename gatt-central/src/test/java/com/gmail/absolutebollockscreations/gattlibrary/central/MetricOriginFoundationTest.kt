package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.central.capture.CaptureTrigger
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class MetricOriginFoundationTest {
    private fun advertisementSource(
        sourceKey: String,
        generation: Long,
    ) = MetricSourceId(
        originId = MetricOriginId.Advertisement(sourceKey, generation),
        protocol = MetricProtocol.VENDOR,
        endpointId = MetricEndpointId.ManufacturerData(0x0102),
    )

    private fun sample(
        source: MetricSourceId,
        metric: MetricKind,
        value: Double,
        arrivalMs: Long,
        freshnessMs: Long? = null,
    ) = MetricSample(
        originId = source.originId,
        sourceId = source,
        protocol = source.protocol,
        sourceClass = MetricSourceClass.VENDOR_NATIVE,
        metric = metric,
        arrivalMs = arrivalMs,
        freshnessMs = freshnessMs,
        value = value,
        nativeCumulative = if (metric.cumulative) value else null,
    )

    @Test
    fun `identities distinguish GATT characteristics from advertisement manufacturer data`() {
        val connection = ConnectionId("AA:BB:CC:DD:EE:FF", 7L)
        val characteristic = UUID.randomUUID()
        val gatt = MetricSourceId(connection, MetricProtocol.FTMS, characteristic)
        val advertisement = advertisementSource("selected-keiser", 3L)

        assertEquals(MetricOriginId.Gatt(connection), gatt.originId)
        assertEquals(MetricEndpointId.GattCharacteristic(characteristic), gatt.endpointId)
        assertEquals(connection, gatt.connectionId)
        assertEquals(characteristic, gatt.characteristicUuid)
        assertEquals(MetricOriginId.Advertisement("selected-keiser", 3L), advertisement.originId)
        assertEquals(MetricEndpointId.ManufacturerData(0x0102), advertisement.endpointId)
        assertNull(advertisement.gattConnectionId)
        assertNull(advertisement.gattCharacteristicUuid)

        assertThrows(IllegalArgumentException::class.java) {
            MetricOriginId.Advertisement(" ", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MetricEndpointId.ManufacturerData(0x1_0000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(advertisement, MetricKind.POWER, 100.0, 1L).copy(
                originId = MetricOriginId.Advertisement("other", 1L),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(advertisement, MetricKind.POWER, 100.0, 1L, freshnessMs = 0L)
        }
    }

    @Test
    fun `sample freshness override wins and its boundary is inclusive`() {
        val source = advertisementSource("freshness", 1L)
        val fusion = MetricFusion(BtEngineState())

        fusion.submit(sample(source, MetricKind.POWER, 200.0, 100L, freshnessMs = 1_000L))
        fusion.refresh(1_100L)
        assertEquals(source, fusion.selectedSource(MetricKind.POWER))

        fusion.refresh(1_101L)
        assertNull(fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `unset freshness retains the existing source-class default`() {
        val source = advertisementSource("default-freshness", 1L)
        val fusion = MetricFusion(BtEngineState())

        fusion.submit(sample(source, MetricKind.POWER, 200.0, 100L))
        fusion.refresh(3_100L)
        assertEquals(source, fusion.selectedSource(MetricKind.POWER))

        fusion.refresh(3_101L)
        assertNull(fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `ending one advertisement generation clears its pin and machine selection only`() {
        val first = advertisementSource("same-bike", 1L)
        val second = advertisementSource("same-bike", 2L)
        val fusion = MetricFusion(BtEngineState())

        fusion.submit(sample(first, MetricKind.POWER, 180.0, 100L))
        fusion.submit(sample(second, MetricKind.POWER, 190.0, 101L))
        fusion.assign(MetricKind.POWER, first, 101L)
        fusion.selectMachineOrigin(first.originId)

        fusion.onOriginEnded(first.originId, 102L)

        assertNull(fusion.manualAssignment(MetricKind.POWER))
        assertNull(fusion.selectedMachineOrigin())
        assertEquals(second, fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `cumulative continuity rebases across advertisement generations`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        val first = advertisementSource("same-bike", 1L)
        val recovered = advertisementSource("same-bike", 2L)

        fusion.submit(sample(first, MetricKind.DISTANCE, 100.0, 100L))
        fusion.submit(sample(first, MetricKind.DISTANCE, 120.0, 101L))
        fusion.onOriginEnded(first.originId, 102L)
        fusion.submit(sample(recovered, MetricKind.DISTANCE, 2.0, 103L))
        assertEquals(120, state.totalDistanceMeters.value)

        fusion.submit(sample(recovered, MetricKind.DISTANCE, 5.0, 104L))
        assertEquals(123, state.totalDistanceMeters.value)
    }

    @Test
    fun `equal advertisement candidates tie break by stable origin not insertion order`() {
        val a = advertisementSource("a-bike", 1L)
        val b = advertisementSource("b-bike", 1L)

        fun winner(first: MetricSourceId, second: MetricSourceId): MetricSourceId? {
            val fusion = MetricFusion(BtEngineState())
            fusion.submit(sample(first, MetricKind.POWER, 200.0, 100L))
            fusion.submit(sample(second, MetricKind.POWER, 200.0, 100L))
            return fusion.selectedSource(MetricKind.POWER)
        }

        assertEquals(a, winner(a, b))
        assertEquals(a, winner(b, a))
    }

    @Test
    fun `advertisement capture labels the origin and endpoint without exposing source key`() {
        class RecordingTree : Timber.Tree() {
            val lines = mutableListOf<String>()

            public override fun isLoggable(tag: String?, priority: Int): Boolean =
                tag == BleCapture.TAG

            public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == BleCapture.TAG) lines += message
            }
        }

        val tree = RecordingTree()
        val rawKey = "AA:BB:CC:DD:EE:FF/system-56"
        try {
            Timber.plant(tree)
            BleCapture.resetSession()
            BleCapture.arm(CaptureTrigger.MANUAL)

            CaptureHooks.sample(sample(advertisementSource(rawKey, 1L), MetricKind.POWER, 200.0, 1L))

            val line = tree.lines.single()
            assertTrue(line, line.contains("\"conn\":\"c1\""))
            assertTrue(line, line.contains("\"sourceId\":\"VENDOR:manufacturer:0102\""))
            assertFalse(line, line.contains(rawKey))
        } finally {
            Timber.uproot(tree)
            BleCapture.resetSession()
        }
    }
}

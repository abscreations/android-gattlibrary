package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class MetricSampleFiltersTest {
    private val connection = ConnectionId("AA:00:00:00:10:01", 10)
    private val source = MetricSourceId(connection, MetricProtocol.HRM, UUID(0, 1))

    private fun sample(value: Double, arrivalMs: Long) = MetricSample(
        connectionId = connection,
        sourceId = source,
        protocol = MetricProtocol.HRM,
        sourceClass = MetricSourceClass.HRM,
        metric = MetricKind.HEART_RATE,
        arrivalMs = arrivalMs,
        value = value,
    )

    @Test
    fun `first sample passes and bounded jump is exponentially smoothed`() {
        val filter = HeartRateJumpEmaFilter()
        assertEquals(100.0, filter.filter(sample(100.0, 0)).value, 0.0)
        assertEquals(106.0, filter.filter(sample(200.0, 1_000)).value, 0.0)
        assertEquals(113.0, filter.filter(sample(200.0, 2_000)).value, 0.0)
    }

    @Test
    fun `reset makes the next reading a new first sample`() {
        val filter = HeartRateJumpEmaFilter()
        filter.filter(sample(100.0, 0))
        filter.filter(sample(200.0, 1_000))
        filter.reset()
        assertEquals(200.0, filter.filter(sample(200.0, 2_000)).value, 0.0)
    }

    @Test
    fun `filter rejects a backwards monotonic timestamp`() {
        val filter = HeartRateJumpEmaFilter()
        filter.filter(sample(100.0, 1_000))
        assertThrows(IllegalArgumentException::class.java) {
            filter.filter(sample(101.0, 999))
        }
    }
}

package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstDisplayMetric

/**
 * The five metric slots the simulated Kayak First panel shows. Simulator-owned (LIB-19l): the
 * console model is independent of central's `KayakFirstDisplayConfiguration`, which is the app's
 * command-side setting. Both decode the same wire codes from `core.vendor.KayakFirstDisplayMetric`.
 */
data class KayakFirstPanelConfiguration(
    val slot1: KayakFirstDisplayMetric,
    val slot2: KayakFirstDisplayMetric,
    val slot3: KayakFirstDisplayMetric,
    val slot4: KayakFirstDisplayMetric,
    val slot5: KayakFirstDisplayMetric,
) {
    val slots: List<KayakFirstDisplayMetric>
        get() = listOf(slot1, slot2, slot3, slot4, slot5)

    fun withSlot(index: Int, metric: KayakFirstDisplayMetric): KayakFirstPanelConfiguration {
        require(index in 0 until SLOT_COUNT)
        val updated = slots.toMutableList().also { it[index] = metric }
        return KayakFirstPanelConfiguration(
            updated[0], updated[1], updated[2], updated[3], updated[4],
        )
    }

    companion object {
        val DEFAULT = KayakFirstPanelConfiguration(
            slot1 = KayakFirstDisplayMetric.ELAPSED_TIME,
            slot2 = KayakFirstDisplayMetric.TOTAL_DISTANCE,
            slot3 = KayakFirstDisplayMetric.AVERAGE_STROKE_RATE,
            slot4 = KayakFirstDisplayMetric.AVERAGE_1000M_PACE,
            slot5 = KayakFirstDisplayMetric.TOTAL_EXPENDED_ENERGY,
        )

        fun fromWireCodes(codes: List<Int>): KayakFirstPanelConfiguration? {
            if (codes.size != SLOT_COUNT) return null
            val metrics = codes.map { KayakFirstDisplayMetric.fromWireCode(it) ?: return null }
            return KayakFirstPanelConfiguration(
                metrics[0],
                metrics[1],
                metrics[2],
                metrics[3],
                metrics[4],
            )
        }

        const val SLOT_COUNT = 5
    }
}

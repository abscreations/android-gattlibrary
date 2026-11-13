package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstDisplayMetric

/**
 * The five metric slots the simulated Kayak First panel shows. Simulator-owned (LIB-19l): the
 * console model is independent of central's `KayakFirstDisplayConfiguration`, which is the app's
 * command-side setting. Both decode the same wire codes from `core.vendor.KayakFirstDisplayMetric`.
 */
public data class KayakFirstPanelConfiguration(
    val slot1: KayakFirstDisplayMetric,
    val slot2: KayakFirstDisplayMetric,
    val slot3: KayakFirstDisplayMetric,
    val slot4: KayakFirstDisplayMetric,
    val slot5: KayakFirstDisplayMetric,
) {
    public val slots: List<KayakFirstDisplayMetric>
        get() = listOf(slot1, slot2, slot3, slot4, slot5)

    public fun withSlot(index: Int, metric: KayakFirstDisplayMetric): KayakFirstPanelConfiguration {
        require(index in 0 until SLOT_COUNT)
        val updated = slots.toMutableList().also { it[index] = metric }
        return KayakFirstPanelConfiguration(
            updated[0], updated[1], updated[2], updated[3], updated[4],
        )
    }

    public companion object {
        public val DEFAULT: KayakFirstPanelConfiguration = KayakFirstPanelConfiguration(
            slot1 = KayakFirstDisplayMetric.ELAPSED_TIME,
            slot2 = KayakFirstDisplayMetric.TOTAL_DISTANCE,
            slot3 = KayakFirstDisplayMetric.AVERAGE_STROKE_RATE,
            slot4 = KayakFirstDisplayMetric.AVERAGE_1000M_PACE,
            slot5 = KayakFirstDisplayMetric.TOTAL_EXPENDED_ENERGY,
        )

        public fun fromWireCodes(codes: List<Int>): KayakFirstPanelConfiguration? {
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

        public const val SLOT_COUNT: Int = 5
    }
}

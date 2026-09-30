package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

/**
 * The 17 display-selector IDs defined by pinned Apache-2.0 TMIW source.
 *
 * These codes configure console display slots. They are not command-6 array indexes; in
 * particular selectors 15, 17, and 18 do not prove a command-6 energy or power field.
 */
public enum class KayakFirstDisplayMetric(public val wireCode: Int, public val displayLabel: String) {
    ELAPSED_TIME(0, "Elapsed Time"),
    TOTAL_DISTANCE(2, "Total Distance"),
    AVERAGE_SPEED(3, "Average Speed"),
    INSTANTANEOUS_SPEED(4, "Instantaneous Speed"),
    AVERAGE_STROKE_RATE(5, "Average Stroke Rate"),
    INSTANTANEOUS_STROKE_RATE(6, "Inst. Stroke Rate"),
    AVERAGE_200M_PACE(7, "Average 200m Pace (sec)"),
    INSTANTANEOUS_200M_PACE(8, "Inst. 200m Pace (sec)"),
    AVERAGE_500M_PACE(9, "Average 500m Pace (sec)"),
    INSTANTANEOUS_500M_PACE(10, "Inst. 500m Pace (sec)"),
    AVERAGE_1000M_PACE(11, "Average 1000m Pace (sec)"),
    INSTANTANEOUS_1000M_PACE(12, "Inst. 1000m Pace (sec)"),
    AVERAGE_FORCE(13, "Average Force"),
    INSTANTANEOUS_FORCE(14, "Instantaneous Force"),
    TOTAL_EXPENDED_ENERGY(15, "Total Expended Energy"),
    INSTANTANEOUS_POWER(17, "Instantaneous Power"),
    AVERAGE_POWER(18, "Average Power"),
    ;

    public companion object {
        private val byWireCode = entries.associateBy(KayakFirstDisplayMetric::wireCode)

        public fun fromWireCode(code: Int): KayakFirstDisplayMetric? = byWireCode[code]
    }
}

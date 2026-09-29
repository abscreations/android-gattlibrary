package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.HEART_RATE_FRESHNESS_MS

/**
 * Substitute a live heart-rate strap's reading for the simulator's generated one.
 *
 * ## Why this exists
 *
 * Every simulator persona that carries heart rate carries a *number that was invented*. That is
 * fine for exercising a client's parser and useless for answering the question Phase 7 actually
 * has: whether the Schwinn 170's supplemental byte 16 is heart rate at all. All 698 captured frames
 * report zero there because no strap was paired, so the offset is consistent with TMIW's reading
 * rather than established by it.
 *
 * Attaching a real strap to the central and letting its value flow out through the simulated
 * machine closes the loop from the client's side: a value written at byte 16 comes back out as
 * heart rate in the official application. **It does not confirm that a physical Schwinn puts it
 * there** — both ends are still FitBridge — but a real, varying, externally-sourced number is a
 * much better probe than a generated one, because it cannot coincidentally match whatever the
 * client expected.
 *
 * ## Where it is applied, and why there
 *
 * At the sample, in `FtmsSimulator.publish`, **before the two consumption paths diverge**. Standard
 * FTMS personas reach a client through `BtEngineState` and `EngineMetricPump`; the proprietary
 * personas hand their `FtmsMockSample` straight to their own encoders. Substituting once at the
 * sample means every persona is covered by one edit — including the 170 when it lands — rather
 * than each encoder growing its own override and one of them being forgotten.
 *
 * ## Reading the strap without reading ourselves
 *
 * The simulator writes its generated heart rate into `BtEngineState.heartRate`, so reading that
 * field back would return the simulator's own value one tick later. [externalBpm] must therefore
 * come from `BtEngineState.externalHeartRate`, which `MetricFusion` populates **only** from a
 * source whose class is `HRM`. That separation already existed; this override simply depends on it.
 */
internal object SimulatorHeartRateOverride {

    /**
     * How recently the strap must have reported for its value to be used.
     *
     * Deliberately the same 4 s window fusion applies to heart rate, rather than a second constant
     * that could drift away from it. A strap notifies about once a second, so this tolerates three
     * missed notifications before falling back — long enough to ride out ordinary contact noise,
     * short enough that a strap taken off does not keep a stale number alive through a whole
     * validation session.
     */
    const val FRESHNESS_MS = HEART_RATE_FRESHNESS_MS

    /** What the override did, so a validation capture can say which frames carried a real strap. */
    enum class Outcome {
        /** No strap has ever reported on this link. The generated value stands, silently. */
        NO_EXTERNAL_SOURCE,

        /** A strap is reporting and its value was used. */
        OVERRIDDEN,

        /** A strap reported and then went quiet. The generated value stands, and it is logged. */
        STALE_FELL_BACK,
    }

    data class Result(val bpm: Int, val outcome: Outcome)

    /**
     * Choose between the strap's reading and the generated one.
     *
     * @param generatedBpm the persona's own value, used whenever the strap cannot be trusted.
     * @param externalBpm `BtEngineState.externalHeartRate`, or null if no strap has reported.
     * @param lastExternalAtMs `BtEngineState.lastExternalAt`; zero means never.
     * @param nowMs the same clock `lastExternalAt` was stamped with.
     *
     * **A strap that has reported and gone quiet is distinguished from one that never existed**,
     * because those need different treatment: the first is a fallback worth recording in a capture,
     * the second is the ordinary case and logging it every second would be noise.
     */
    fun resolve(
        generatedBpm: Int,
        externalBpm: Int?,
        lastExternalAtMs: Long,
        nowMs: Long,
    ): Result {
        if (externalBpm == null || lastExternalAtMs <= 0L) {
            return Result(generatedBpm, Outcome.NO_EXTERNAL_SOURCE)
        }
        // A timestamp from the future is a clock that moved, not a fresh reading. Treating it as
        // fresh would pin a stale value in place indefinitely.
        val age = nowMs - lastExternalAtMs
        if (age in 0..FRESHNESS_MS) {
            return Result(externalBpm, Outcome.OVERRIDDEN)
        }
        return Result(generatedBpm, Outcome.STALE_FELL_BACK)
    }
}

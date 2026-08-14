package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.ActivityType
import timber.log.Timber
import java.util.UUID

/**
 * Schwinn x70 protocol handler — 170 / 270 / 570 (plan §7), measurement-only.
 *
 * ## One handler, two consoles
 *
 * The family shares a service, a namespace and an initialization command, and nothing else. A 270
 * or 570 notifies 17-byte frames on the measurement characteristic; a 170 notifies paired 20- and
 * 14-byte frames on two others and produces no 17-byte frame at all. Both are handled here because
 * a device cannot be told apart until its notifications arrive — the console reports the identity
 * `SCHWINN 170/270` either way, so there is nothing at discovery time to branch on.
 *
 * [planSchwinnX70Discovery] therefore subscribes to whichever of the three streams the GATT
 * database actually offers, and the notification router keys on the characteristic. **Nothing
 * dispatches on payload length**, which is QZ's one structural defect here: it ignores the
 * characteristic entirely, so any 20-octet notification from any source would be read as this
 * family's supplemental frame.
 *
 * ## The initialization write, and what "fail closed" means
 *
 * The console sends nothing until `1717b3c0-…` is written. FitBridge sends **QZ's** literal
 * `07 01 D3 00 1F 05 01`, because that is the command a physical console was observed accepting in
 * the issue #1365 HCI; TMIW's `05 03 D9 00 1F` is a second, unreconciled sample (V17). The framing
 * rule — byte 0 is the length, all octets sum to `0x100` — is *verified* before the write and never
 * used to synthesise one. A third invented command would repeat the V13 mistake.
 *
 * §7 requires this to fail closed: if the streams never arrive, report it, do not retry blindly.
 * That is met by structure rather than by a timer. The write happens **once per link generation**
 * and there is no retry path at all, so a console that ignores it produces a connection that
 * subscribes and receives nothing — which §0.7a's liveness path already demotes and surfaces. A
 * handler that retried would be indistinguishable from one that worked, right up until the user
 * noticed an empty session.
 *
 * ## Control
 *
 * None. The `1717b3c0-…` write starts a notification stream; it is setup, not movement control, so
 * D26 does not block it. No capture contains a resistance target or a start/stop exchange, and §9.2
 * sets no target bits for this family.
 */
internal class SchwinnX70ProtocolHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(BluetoothIds.UUID_X70_SERVICE)

    override val extraCharacteristics: Set<UUID> = setOf(
        BluetoothIds.UUID_X70_MEASUREMENT,
        BluetoothIds.UUID_X70_SUPPLEMENTAL,
        BluetoothIds.UUID_X70_SIBLING,
        BluetoothIds.UUID_X70_CONTROL,
    )

    /**
     * Link-scoped state. **All of it dies with the link**, which is why it is reset from both
     * [onConnected] and [onDisconnected] rather than only the latter: a reconnection that reused a
     * stale crank counter would compute a cadence from two different sessions.
     */
    private var previousCounter: Int? = null
    private var previousCounterAtMs: Long = 0L
    private var dampedPower: SchwinnX70PowerModel.DampedPower? = null
    private var dampedAtMs: Long = 0L
    private var sessionFirstMain: SchwinnX70Measurement.Main? = null
    private var sessionFirstAtMs: Long = 0L
    private var lastSupplemental: SchwinnX70Measurement.Supplemental? = null
    private var previousSequence: Int? = null
    private var initialisationWritten = false
    private var deviceClassificationPublished = false
    private var rejectedFrames = 0

    internal fun resetLinkState() {
        previousCounter = null
        previousCounterAtMs = 0L
        dampedPower = null
        dampedAtMs = 0L
        sessionFirstMain = null
        sessionFirstAtMs = 0L
        lastSupplemental = null
        previousSequence = null
        initialisationWritten = false
        deviceClassificationPublished = false
        rejectedFrames = 0
    }

    /** Frames rejected on this link, for the §11 evidence record and diagnostic capture. */
    internal fun rejectedFrameCount(): Int = rejectedFrames

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) = resetLinkState()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) = resetLinkState()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val inventory = services.associate { service ->
            service.uuid to service.characteristics.mapTo(linkedSetOf()) { it.uuid }
        }
        val targets = planSchwinnX70Discovery(inventory)
        if (targets.isEmpty()) return false

        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("Schwinn x70 discovery ignored for unregistered GATT")
            return false
        }

        val service = services.first { it.uuid == BluetoothIds.UUID_X70_SERVICE }
        for (target in targets) {
            service.characteristics
                .firstOrNull { it.uuid == target.characteristicUuid }
                ?.let { engine.enableNotifications(context, it) }
        }
        Timber.tag(BtEngineState.TAG).i(
            "Schwinn x70 activating with %d stream(s) on %s",
            targets.size,
            gatt.device?.address,
        )

        // Ordered after the CCCDs deliberately. The console starts notifying in response to this
        // write, so arming first means no frame can be produced before there is somewhere to put
        // it. That is the opposite of the Life Fitness ordering, where the captures show the
        // measurement CCCDs armed only after the proprietary exchange — evidence decides each, and
        // neither order is a house style.
        if (!initialisationWritten && inventory[BluetoothIds.UUID_X70_SERVICE]
                ?.contains(BluetoothIds.UUID_X70_CONTROL) == true
        ) {
            initialisationWritten = true
            engine.pushWrite(context, SCHWINN_X70_INITIALISATION, BluetoothIds.UUID_X70_CONTROL)
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val arrivalMs = MonotonicMetricClock.SYSTEM.nowMs()
        val samples = samplesForNotification(
            characteristic = ch.uuid,
            value = value,
            arrivalMs = arrivalMs,
            athleteMassKg = engine.state.lifeFitnessProfile.weightKg.toDouble(),
        )
        if (samples.isEmpty()) return

        if (!deviceClassificationPublished) {
            gatt.device?.address?.let { publishDeviceClassification(engine.state, it) }
        }

        val emitter = engine.metricEmitterFor(
            gatt,
            ch.uuid,
            MetricProtocol.VENDOR,
            MetricSourceClass.VENDOR_NATIVE,
            arrivalMs,
        ) ?: return

        for (sample in samples) {
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = sample.eventMs,
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
            )
        }
    }

    /**
     * Route one notification to its decoder and produce the metrics it implies.
     *
     * **Internal and free of every Android type**, which is deliberate rather than incidental. The
     * three decoders below carry all of this handler's link state — a crank counter, a damping
     * baseline, an energy baseline, a held supplemental frame — and state that only a device can
     * exercise is state nothing verifies. Splitting the routing from the callback leaves
     * [onCharacteristicChanged] as the part that genuinely needs hardware: a `BluetoothGatt`, an
     * emitter, and nothing to get wrong.
     *
     * Dispatch is on the characteristic and never on payload length — QZ's structural defect here.
     */
    internal fun samplesForNotification(
        characteristic: UUID,
        value: ByteArray,
        arrivalMs: Long,
        athleteMassKg: Double = SchwinnX70PowerModel.DEFAULT_ATHLETE_MASS_KG,
    ): List<SchwinnX70Sample> = when (characteristic) {
        BluetoothIds.UUID_X70_MEASUREMENT -> mainSamples(value, arrivalMs, athleteMassKg)
        BluetoothIds.UUID_X70_SUPPLEMENTAL -> supplementalSamples(value)
        BluetoothIds.UUID_X70_SIBLING -> siblingSamples(value, arrivalMs)
        else -> emptyList()
    }

    /** 270 / 570 main frame: cadence across frames, then the modelled power and speed. */
    private fun mainSamples(value: ByteArray, arrivalMs: Long, athleteMassKg: Double): List<SchwinnX70Sample> {
        val main = SchwinnX70Measurement.parseMain(value) ?: return rejected(value)

        val cadence = previousCounter?.let { previous ->
            SchwinnX70Emission.cadenceRpm(
                previous,
                main.cadenceEventCounter,
                (arrivalMs - previousCounterAtMs) / 1000.0,
            )
        }
        previousCounter = main.cadenceEventCounter
        previousCounterAtMs = arrivalMs

        val power = cadence?.let {
            val estimate = SchwinnX70PowerModel.powerWatts(it.toInt(), main.resistance)
            val damped = SchwinnX70PowerModel.damp(
                dampedPower,
                estimate,
                (arrivalMs - dampedAtMs) / 1000.0,
            )
            dampedPower = damped
            dampedAtMs = arrivalMs
            damped
        }

        // The energy baseline is the first frame of this link, not the console's zero: the machine
        // may have been accumulating long before FitBridge connected.
        val first = sessionFirstMain ?: main.also {
            sessionFirstMain = it
            sessionFirstAtMs = arrivalMs
        }
        val energy = SchwinnX70Measurement.publishableKcal(
            first,
            main,
            (arrivalMs - sessionFirstAtMs) / 1000.0,
        )

        return SchwinnX70Emission.samplesFor270(
            main = main,
            cadenceRpm = cadence,
            dampedPower = power,
            sessionEnergyKcal = energy,
            // The athlete's mass enters the D74 speed model through rolling resistance. FitBridge
            // already collects it for the Life Fitness handshake, so a real value is available;
            // the settings default is used when the user has not changed it, which is the same
            // assumption TMIW makes unconditionally.
            athleteMassKg = athleteMassKg,
        )
    }

    /**
     * 170 supplemental frame: held, not emitted.
     *
     * Heart rate and resistance are published alongside the sibling frame's speed and power so that
     * one tick produces one coherent set. The two streams arrive one-for-one — 698 and 698 in the
     * capture — so holding the most recent costs nothing and avoids emitting a heart rate against a
     * stale speed.
     */
    private fun supplementalSamples(value: ByteArray): List<SchwinnX70Sample> {
        val supplemental = SchwinnX70Measurement.parseSupplemental(value) ?: return rejected(value)
        lastSupplemental = supplemental
        return emptyList()
    }

    /**
     * 170 sibling frame: native speed and power, with the held supplemental fields.
     *
     * **No cadence.** The 170's byte 0 is a frame sequence counter, not a crank counter, and
     * neither captured stream carries a revolution count anywhere. Turning the sequence into rpm
     * would produce a rate of *packets* wearing the units of a rate of *pedal strokes* — a number
     * that looks entirely reasonable and means nothing. A 170 rider therefore sees speed, power and
     * heart rate, and no cadence, until a capture shows where cadence lives.
     */
    private fun siblingSamples(value: ByteArray, arrivalMs: Long): List<SchwinnX70Sample> {
        val sibling = SchwinnX70Measurement.parseSibling(value) ?: return rejected(value)

        val dropped = previousSequence?.let { (sibling.sequence - it + 256) % 256 } ?: 1
        if (dropped > 1) {
            Timber.tag(BtEngineState.TAG).v("Schwinn x70 sibling gap of %d frame(s)", dropped - 1)
        }
        previousSequence = sibling.sequence

        return SchwinnX70Emission.samplesFor170(
            sibling = sibling,
            supplemental = lastSupplemental,
            cadenceRpm = null,
        )
    }

    private fun rejected(value: ByteArray): List<SchwinnX70Sample> {
        rejectedFrames += 1
        Timber.tag(BtEngineState.TAG).v(
            "Schwinn x70 frame rejected (len=%d, total=%d)",
            value.size,
            rejectedFrames,
        )
        return emptyList()
    }

    /** Every console in this family is an upright bike. */
    internal fun persona(): FusionPersona = FusionPersona.RIDE

    internal fun publishDeviceClassification(state: BtEngineState, address: String) {
        state.updateDeviceMachineFeatureByAddress(address, ActivityType.BIKING.ordinal)
        deviceClassificationPublished = true
    }
}

/**
 * The initialization command, sent verbatim (V17).
 *
 * QZ's literal, chosen because a physical console was observed accepting it. Byte 0 is the frame
 * length and every octet sums to `0x100`; both properties are asserted by test rather than computed
 * here, because a command FitBridge generated would be a third value for a field where two already
 * disagree and nothing can choose between them.
 */
internal val SCHWINN_X70_INITIALISATION: ByteArray = byteArrayOf(
    0x07, 0x01, 0xD3.toByte(), 0x00, 0x1F, 0x05, 0x01,
)

/**
 * Pure discovery contract: subscribe to whichever measurement streams this console offers.
 *
 * Returns an empty list when the service is absent or exposes none of the three notify
 * characteristics — a service UUID is evidence of identity, not a promise of data, and declining to
 * activate leaves the link free for any other handler that does match (§0.7a).
 *
 * **Both console shapes must survive this.** A 270 offers the measurement characteristic and not
 * the 170 pair; a 170 the reverse. Requiring all three would produce a handler that activates only
 * for a device that does not exist, which is the failure the §7 registry test guards against on the
 * matcher side and this function guards on the subscription side.
 */
internal fun planSchwinnX70Discovery(inventory: Map<UUID, Set<UUID>>): List<CccdTarget> {
    val characteristics = inventory[BluetoothIds.UUID_X70_SERVICE] ?: return emptyList()
    return listOf(
        BluetoothIds.UUID_X70_MEASUREMENT,
        BluetoothIds.UUID_X70_SUPPLEMENTAL,
        BluetoothIds.UUID_X70_SIBLING,
    ).filter { it in characteristics }
        .map { CccdTarget(serviceUuid = BluetoothIds.UUID_X70_SERVICE, characteristicUuid = it) }
}

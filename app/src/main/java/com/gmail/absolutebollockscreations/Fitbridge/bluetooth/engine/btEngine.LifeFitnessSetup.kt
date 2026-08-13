package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import androidx.annotation.RequiresPermission
import timber.log.Timber

/**
 * The engine side of the Life Fitness setup run (plan §6, unit 6.5b).
 *
 * **This file is the plumbing and nothing else.** Every decision — whether a machine needs
 * setup, whether the profile is complete, what to send, what to do when a step fails — lives in
 * `LifeFitnessFingerprint`, `lifeFitnessSetupGate`, `LifeFitnessSetup` and
 * `LifeFitnessSetupDriver`, all of which are pure and exhaustively tested. What remains here is
 * issuing writes, routing completions, and reporting the outcome to the adapter policy.
 *
 * That division is deliberate: this is the only part of the Life Fitness path no unit test in
 * this project can reach, so it was made as small as the problem allows.
 */

/**
 * Begin the setup run for a machine that has just completed discovery, if it needs one.
 *
 * Called at the end of FTMS service discovery, **before** the measurement CCCDs are armed. That
 * is the order the captures show: QZ issue #1018 enables the standard FTMS notifications only
 * after the proprietary exchange completes, which is what a machine that stays silent until it
 * is unlocked would require. Arming first and unlocking second would work too, but there is no
 * evidence for it and evidence for the other way round.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.startLifeFitnessSetupIfNeeded(context: ConnectionContext) {
    if (context.lifeFitnessSetup != null) return

    val driver = LifeFitnessSetupDriver()
    val progress = driver.start(
        services = context.discoveredServices.toLifeFitnessInventory(),
        profile = state.lifeFitnessProfile,
        ageYears = state.lifeFitnessAgeYears,
        issue = { op -> issueLifeFitnessOperation(context, op) },
    )

    when (progress) {
        is LifeFitnessSetupDriver.Progress.NotApplicable -> Unit // Not this family; say nothing.

        is LifeFitnessSetupDriver.Progress.NeedsProfile -> {
            // The connection stands. The machine will stay silent because its handshake has not
            // run, which `SETUP_REQUIRED` already represents. The prompt that asks for the fields
            // is the UI's job (6.5c), so what the engine publishes is which fields are missing —
            // names only, never values, and the log carries only a count.
            Timber.tag(BtEngineState.TAG).i(
                "Life Fitness setup deferred: %d profile field(s) not supplied",
                progress.missing.size,
            )
            state.setLifeFitnessProfilePrompt(progress.missing)
        }

        else -> {
            context.lifeFitnessSetup = driver
            applyLifeFitnessProgress(context, progress)
        }
    }
}

/** Feed a write or descriptor-write completion into the run that is waiting on it. */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.advanceLifeFitnessSetup(gatt: BluetoothGatt, success: Boolean) {
    val context = state.connectionRegistry.contextForGatt(gatt) ?: return
    val driver = context.lifeFitnessSetup ?: return
    applyLifeFitnessProgress(
        context,
        driver.onCompleted(success) { op -> issueLifeFitnessOperation(context, op) },
    )
}

/**
 * Expire a setup step whose completion never arrived.
 *
 * Driven from the engine's existing one-second liveness tick rather than a timer per operation:
 * a poll is what a timeout needs anyway, and reusing the tick means the deadline dies with the
 * connection context instead of being a callback that must be remembered and cancelled. A stale
 * timer resolving into a live link is the failure this avoids by construction.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.tickLifeFitnessSetup(nowMs: Long) {
    for (context in state.connectionRegistry.machineContexts()) {
        val driver = context.lifeFitnessSetup ?: continue
        val deadline = context.lifeFitnessSetupDeadlineMs ?: continue
        if (nowMs < deadline) continue

        applyLifeFitnessProgress(
            context,
            driver.onTimeout { op -> issueLifeFitnessOperation(context, op) },
        )
    }
}

/** Drop the run when its link ends. A transcript must not survive the connection that began it. */
internal fun BtEngine.clearLifeFitnessSetup(context: ConnectionContext) {
    context.lifeFitnessSetup?.reset()
    context.lifeFitnessSetup = null
    context.lifeFitnessSetupDeadlineMs = null
}

/**
 * Turn driver progress into engine effects: arm or clear the settle bound, and tell the policy.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.applyLifeFitnessProgress(
    context: ConnectionContext,
    progress: LifeFitnessSetupDriver.Progress,
) {
    when (progress) {
        is LifeFitnessSetupDriver.Progress.Running -> {
            context.lifeFitnessSetupDeadlineMs =
                MonotonicMetricClock.SYSTEM.nowMs() + LifeFitnessSetup.SETTLE_TIMEOUT_MS
        }

        is LifeFitnessSetupDriver.Progress.Succeeded -> {
            context.lifeFitnessSetupDeadlineMs = null
            Timber.tag(BtEngineState.TAG).i("Life Fitness setup complete")
            state.adapterLiveness.setupSucceeded(context.connectionId)
        }

        is LifeFitnessSetupDriver.Progress.Failed -> {
            context.lifeFitnessSetupDeadlineMs = null
            // `detail` names the step and never the payload — see `LifeFitnessSetup.Op`.
            Timber.tag(BtEngineState.TAG).w(progress.detail)
            state.adapterLiveness.setupFailed(context.connectionId, progress.detail)
        }

        // Classification outcomes are handled by the caller that started the run; reaching them
        // here would mean the driver reclassified mid-run, which it cannot do.
        is LifeFitnessSetupDriver.Progress.NotApplicable,
        is LifeFitnessSetupDriver.Progress.NeedsProfile,
        -> context.lifeFitnessSetupDeadlineMs = null
    }
}

/**
 * Put one transcript operation on the connection's queue.
 *
 * Returns whether it was accepted. **A missing characteristic returns false rather than
 * throwing**, and the driver treats that exactly as it treats a rejection — the step did not
 * happen — so a machine whose GATT changed between discovery and setup fails the run visibly
 * instead of skipping a write.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BtEngine.issueLifeFitnessOperation(
    context: ConnectionContext,
    op: LifeFitnessSetup.Op,
): Boolean {
    val gatt = context.gatt ?: return false
    val characteristic = gatt.getService(op.serviceUuid)
        ?.getCharacteristic(op.characteristicUuid)
        ?: return false

    return when (op.kind) {
        LifeFitnessSetup.OpKind.ENABLE_STATUS -> {
            enableNotifications(context, characteristic)
            true
        }

        LifeFitnessSetup.OpKind.CONTROL_WRITE,
        LifeFitnessSetup.OpKind.PROFILE_WRITE,
        -> {
            val payload = op.payload ?: return false
            pushWrite(context, payload, op.characteristicUuid)
            true
        }
    }
}

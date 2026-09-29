package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.PeripheralConnection

class KickrGattRegistryTest {
    @Test
    fun `CORE 2 standard services produce live CPS and FTMS measurement frames`() {
        val model = KickrSyntheticModel().also { it.startOrResume() }
        val snapshot = model.advance(2.0)
        val frames = kickrStandardMeasurementFrames(
            KickrPersonaManifest.forPersona(KickrSimulatorPersona.CORE2_FC82_PROVISIONAL),
            snapshot,
        )

        assertEquals(
            setOf(
                BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
                BluetoothIds.UUID_BIKING_DATA,
                BluetoothIds.UUID_HEART_RATE_MEASUREMENT,
            ),
            frames.mapTo(linkedSetOf()) { it.characteristicUuid },
        )
        assertEquals(snapshot.powerWatts, frames.single {
            it.characteristicUuid == BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT
        }.value.let { (it[2].toInt() and 0xFF) or (it[3].toInt() shl 8) })
        assertEquals(snapshot.powerWatts, frames.single {
            it.characteristicUuid == BluetoothIds.UUID_BIKING_DATA
        }.value.let { (it[6].toInt() and 0xFF) or (it[7].toInt() shl 8) })
        assertEquals(
            snapshot.heartRateBpm,
            (frames.single { it.characteristicUuid == BluetoothIds.UUID_HEART_RATE_MEASUREMENT }
                .value[1].toInt() and 0xFF),
        )
    }

    @Test
    fun `Android registry creates every service in each pure manifest`() {
        KickrSimulatorPersona.entries.forEach { persona ->
            val pure = KickrPersonaManifest.forPersona(persona)
            val android = KickrGattRegistry.buildServices(pure)
            assertEquals(pure.services.size, android.size)
        }
    }

    @Test
    fun `Android flags preserve manifest properties and permissions`() {
        KickrSimulatorPersona.entries
            .flatMap { KickrPersonaManifest.forPersona(it).services }
            .flatMap { it.characteristics }
            .forEach { characteristic ->
                val (actualProperties, actualPermissions) = KickrGattRegistry.flags(characteristic.properties)
                assertEquals(properties(characteristic.properties), actualProperties)
                val expectedPermissions =
                    (if (characteristic.properties.any { it.name == "READ" }) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                        (if (characteristic.properties.any { it.name.startsWith("WRITE") }) BluetoothGattCharacteristic.PERMISSION_WRITE else 0)
                assertEquals(expectedPermissions, actualPermissions)
            }
    }

    @Test
    fun `per-link session isolates subscriptions route and bounded queue`() {
        val session = KickrPeripheralSession(
            7L,
            KickrSyntheticModel(),
            KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC,
            queueCapacity = 2,
        )
        session.subscribe(BluetoothIds.UUID_BIKING_DATA, PeripheralConnection.CccdMode.NOTIFY)
        assertEquals(PeripheralConnection.CccdMode.NOTIFY, session.subscription(BluetoothIds.UUID_BIKING_DATA))
        assertTrue(session.acquire(KickrControlRoute.FTMS))
        assertFalse(session.acquire(KickrControlRoute.LEGACY_WAHOO))

        repeat(3) { index ->
            session.enqueue(KickrTxFrame(BluetoothIds.UUID_BIKING_DATA, byteArrayOf(index.toByte()), false))
        }
        assertEquals(1L, session.droppedFrames)
        assertEquals(2, session.pendingCount)
        assertEquals(1, session.beginNext()!!.value[0].toInt())
        assertNull(session.beginNext())
        session.completeInFlight()
        assertEquals(2, session.beginNext()!!.value[0].toInt())

        session.clear()
        assertEquals(PeripheralConnection.CccdMode.DISABLED, session.subscription(BluetoothIds.UUID_BIKING_DATA))
        assertNull(session.route)
        assertNull(session.inFlight)
        assertEquals(0, session.pendingCount)
        assertFalse(session.legacy!!.unlocked)
    }

    @Test
    fun `coalescing keeps only the newest acknowledgement for an opcode`() {
        val session = KickrPeripheralSession(8L, KickrSyntheticModel(), queueCapacity = 3)
        session.enqueueCoalesced(KickrTxFrame(BluetoothIds.UUID_WAHOO_KICKR_CONTROL, byteArrayOf(1, 0x42, 1), true))
        session.enqueueCoalesced(KickrTxFrame(BluetoothIds.UUID_WAHOO_KICKR_CONTROL, byteArrayOf(1, 0x42, 2), true))
        assertEquals(1, session.pendingCount)
        assertEquals(2, session.beginNext()!!.value[2].toInt())
    }

    @Test
    fun `route is acquired only by successful FTMS request control or legacy unlock`() {
        val ftmsFirst = KickrPeripheralSession(
            9L,
            KickrSyntheticModel(),
            KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC,
        )
        ftmsFirst.subscribe(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT, PeripheralConnection.CccdMode.INDICATE)
        ftmsFirst.subscribe(BluetoothIds.UUID_WAHOO_KICKR_CONTROL, PeripheralConnection.CccdMode.INDICATE)

        val deniedTarget = ftmsFirst.handleFtmsControl(byteArrayOf(0x05, 0xFA.toByte(), 0x00))
        assertEquals(0x05, deniedTarget!!.response[1].toInt())
        assertNull(ftmsFirst.route)
        ftmsFirst.handleFtmsControl(byteArrayOf(0x00))
        assertEquals(KickrControlRoute.FTMS, ftmsFirst.route)
        assertNull(ftmsFirst.handleLegacyControl(byteArrayOf(0x20, 0xEE.toByte(), 0xFC.toByte())))
        assertFalse(ftmsFirst.legacy!!.unlocked)

        val legacyFirst = KickrPeripheralSession(
            10L,
            KickrSyntheticModel(),
            KickrLegacyAckMode.SILENT_CORROBORATED,
        )
        legacyFirst.subscribe(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT, PeripheralConnection.CccdMode.INDICATE)
        legacyFirst.subscribe(BluetoothIds.UUID_WAHOO_KICKR_CONTROL, PeripheralConnection.CccdMode.INDICATE)
        assertTrue(legacyFirst.handleLegacyControl(byteArrayOf(0x20, 0xEE.toByte(), 0xFC.toByte()))!!.accepted)
        assertEquals(KickrControlRoute.LEGACY_WAHOO, legacyFirst.route)
        assertNull(legacyFirst.handleFtmsControl(byteArrayOf(0x00)))
        assertFalse(legacyFirst.ftms.session.hasControl)
    }

    @Test
    fun `FTMS circumference setup applies without claiming the KICKR control route`() {
        val model = KickrSyntheticModel()
        val session = KickrPeripheralSession(13L, model)
        session.subscribe(
            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT,
            PeripheralConnection.CccdMode.INDICATE,
        )

        val circumference = session.handleFtmsControl(
            byteArrayOf(FtmsSpec.Opcode.SET_WHEEL_CIRCUMFERENCE, 0xDC.toByte(), 0x50),
        )

        assertEquals(FtmsSpec.Result.SUCCESS, circumference!!.response[2])
        assertEquals(2070.0, model.snapshot().wheelCircumferenceMm, 0.0)
        assertFalse(session.ftms.session.hasControl)
        assertNull(session.route)
        session.completeInFlight()

        val power = session.handleFtmsControl(
            byteArrayOf(FtmsSpec.Opcode.SET_TARGET_POWER, 0xA0.toByte(), 0x00),
        )
        assertEquals(FtmsSpec.Result.CONTROL_NOT_PERMITTED, power!!.response[2])
        assertNull(session.route)
    }

    @Test
    fun `original Zwift route requires both CCCDs and excludes FTMS`() {
        val session = KickrPeripheralSession(
            11L,
            KickrSyntheticModel(),
            zwiftServiceForm = RideOnServiceForm.ORIGINAL,
        )
        session.subscribe(RideOnUuids.ASYNC_TX, PeripheralConnection.CccdMode.NOTIFY)
        assertFalse(session.handleZwiftControl(RideOnUuids.HANDSHAKE_ORIGINAL)!!.accepted)
        assertNull(session.route)
        session.subscribe(RideOnUuids.SYNC_TX, PeripheralConnection.CccdMode.INDICATE)
        assertTrue(session.handleZwiftControl(RideOnUuids.HANDSHAKE_ORIGINAL)!!.accepted)
        assertEquals(KickrControlRoute.ZWIFT_PROPRIETARY, session.route)
        assertEquals(RideOnUuids.SYNC_TX, session.beginNext()!!.characteristicUuid)
        session.subscribe(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT, PeripheralConnection.CccdMode.INDICATE)
        assertNull(session.handleFtmsControl(byteArrayOf(0x00)))
        session.clear()
        assertFalse(session.zwift!!.controlReady)
        assertNull(session.route)
    }

    @Test
    fun `CORE2 session accepts real Zwift handshake regardless of the 02 02 or 02 03 marker`() {
        // A 2026-08-30 three-persona btsnoop capture showed real Zwift always writes the 02 03
        // form byte, never 02 02, no matter which RideOn service UUID it discovered - so
        // acceptance no longer depends on the session's configured RideOnServiceForm. See the
        // KDoc on ZwiftTrainerPeripheralServer.handle().
        val session = KickrPeripheralSession(
            12L,
            KickrSyntheticModel(),
            zwiftServiceForm = RideOnServiceForm.CORE2,
        )
        session.subscribe(RideOnUuids.ASYNC_TX, PeripheralConnection.CccdMode.NOTIFY)
        session.subscribe(RideOnUuids.SYNC_TX, PeripheralConnection.CccdMode.INDICATE)
        assertTrue(session.handleZwiftControl(RideOnUuids.HANDSHAKE_CORE2)!!.accepted)
        assertEquals(KickrControlRoute.ZWIFT_PROPRIETARY, session.route)
    }

    @Test
    fun `captured Zwift Hub exposes standard measurements and negotiates original service`() {
        val manifest = KickrPersonaManifest.forPersona(KickrSimulatorPersona.ZWIFT_HUB_CAPTURED)
        val session = KickrPeripheralSession(
            14L,
            KickrSyntheticModel(),
            legacyAckMode = manifest.legacyAckMode,
            zwiftServiceForm = manifest.zwiftServiceForm,
        )

        assertNull(session.legacy)
        assertTrue(kickrStandardMeasurementFrames(manifest, session.model.snapshot()).isNotEmpty())
        session.subscribe(RideOnUuids.ASYNC_TX, PeripheralConnection.CccdMode.NOTIFY)
        session.subscribe(RideOnUuids.SYNC_TX, PeripheralConnection.CccdMode.INDICATE)
        // Real Zwift wrote "RideOn" 02 03 to this persona's 128-bit ORIGINAL service in the
        // 2026-08-30 capture, not 02 02 - both must be accepted (see the CORE2-session test
        // above and the KDoc on ZwiftTrainerPeripheralServer.handle()).
        assertTrue(session.handleZwiftControl(RideOnUuids.HANDSHAKE_CORE2)!!.accepted)
        assertEquals(KickrControlRoute.ZWIFT_PROPRIETARY, session.route)
    }

    private fun properties(values: Set<KickrGattProperty>): Int {
        var result = 0
        if (KickrGattProperty.READ in values) result = result or BluetoothGattCharacteristic.PROPERTY_READ
        if (KickrGattProperty.WRITE in values) result = result or BluetoothGattCharacteristic.PROPERTY_WRITE
        if (KickrGattProperty.WRITE_WITHOUT_RESPONSE in values) {
            result = result or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        }
        if (KickrGattProperty.NOTIFY in values) result = result or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        if (KickrGattProperty.INDICATE in values) result = result or BluetoothGattCharacteristic.PROPERTY_INDICATE
        return result
    }
}

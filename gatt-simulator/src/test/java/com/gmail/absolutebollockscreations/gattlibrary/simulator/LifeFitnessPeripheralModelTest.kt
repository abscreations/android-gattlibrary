package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class LifeFitnessPeripheralModelTest {
    @Test
    fun `the four captured personas preserve explicit advertising identity within legacy budgets`() {
        val expected = listOf(
            PersonaIdentity(LifeFitnessMachine.TREADMILL, "LF1F17B1D000001094", byteArrayOf(0x01, 0x01, 0x00)),
            PersonaIdentity(LifeFitnessMachine.CROSS_TRAINER, "LF1F17B1D000002089", byteArrayOf(0x01, 0x02, 0x00)),
            PersonaIdentity(LifeFitnessMachine.STAIR_CLIMBER, "LF0F17B1D000003095", byteArrayOf(0x01, 0x08, 0x00)),
            PersonaIdentity(LifeFitnessMachine.INDOOR_BIKE, "LF0F17B1D000004090", byteArrayOf(0x01, 0x20, 0x00)),
        )

        expected.forEach { identity ->
            val plan = LifeFitnessAdvertisingPlan.create(identity.machine)
            assertEquals(identity.name, SimulatorPersona.LifeFitness(identity.machine).advertisedName)
            assertEquals(identity.name, plan.localName)
            assertEquals(
                listOf(FtmsUuids.UUID_FTMS_SERVICE),
                plan.advertisedServices,
            )
            assertEquals(FtmsUuids.UUID_FTMS_SERVICE, plan.serviceDataService)
            assertArrayEquals(identity.serviceData, plan.serviceData)
            assertTrue(plan.advertisingBytes <= LifeFitnessAdvertisingPlan.MAX_LEGACY_BYTES)
            assertTrue(plan.scanResponseBytes <= LifeFitnessAdvertisingPlan.MAX_LEGACY_BYTES)
        }
    }

    @Test
    fun `Gatt inventory selects only the active measurement and exposes no FTMS control point`() {
        LifeFitnessMachine.entries.forEach { machine ->
            val services = LifeFitnessGattProfile.manifest(machine)
            val ftms = services.single { it.uuid == FtmsUuids.UUID_FTMS_SERVICE }
            assertTrue(ftms.characteristics.any { it.uuid == machine.measurementUuid })
            assertFalse(ftms.characteristics.any { it.uuid == FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT })
            assertEquals(
                1,
                ftms.characteristics.count {
                    it.uuid in LifeFitnessMachine.entries.map(LifeFitnessMachine::measurementUuid)
                },
            )
            assertEquals(
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                ftms.characteristics.single { it.uuid == machine.measurementUuid }.properties,
            )
            val features = lifeFitnessFeatureValue(machine)
            assertEquals(8, features.size)
            assertArrayEquals(byteArrayOf(0, 0, 0, 0), features.copyOfRange(4, 8))
            val measurementBits = littleEndianU16(features, 0) or (littleEndianU16(features, 2) shl 16)
            assertFalse(measurementBits and (1 shl FtmsSpec.Feature.PACE) != 0)
        }
    }

    @Test
    fun `capture parity manifest pins service order and proprietary properties`() {
        val services = LifeFitnessGattProfile.manifest(LifeFitnessMachine.TREADMILL)
        assertEquals(
            listOf(
                SigUuids.UUID_BOND_MANAGEMENT_SERVICE,
                FtmsUuids.UUID_FTMS_SERVICE,
                LifeFitnessUuids.UUID_LF_SECONDARY_SERVICE,
                LifeFitnessUuids.UUID_LF_FD00_SERVICE,
                SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
                LifeFitnessUuids.UUID_LF_USER_SERVICE,
                LifeFitnessUuids.UUID_LF_CONTROL_SERVICE,
            ),
            services.map(LifeFitnessGattService::uuid),
        )
        assertFalse(services.single { it.uuid == LifeFitnessUuids.UUID_LF_SECONDARY_SERVICE }.primary)
        assertTrue(services.filterNot { it.uuid == LifeFitnessUuids.UUID_LF_SECONDARY_SERVICE }.all { it.primary })

        val user = services.single { it.uuid == LifeFitnessUuids.UUID_LF_USER_SERVICE }
        assertEquals(13, user.characteristics.size)
        assertTrue(
            user.characteristics.all {
                it.properties == (BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
            },
        )

        val control = services.single { it.uuid == LifeFitnessUuids.UUID_LF_CONTROL_SERVICE }
        val expected = mapOf(
            LifeFitnessUuids.UUID_LF_B90_READ_5AA9 to BluetoothGattCharacteristic.PROPERTY_READ,
            LifeFitnessUuids.UUID_LF_STATUS to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            LifeFitnessUuids.UUID_LF_CONTROL_1 to BluetoothGattCharacteristic.PROPERTY_WRITE,
            LifeFitnessUuids.UUID_LF_B90_RW_NOTIFY_52A6 to
                (BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY),
            LifeFitnessUuids.UUID_LF_B90_NOTIFY_5AA8 to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            LifeFitnessUuids.UUID_LF_B90_READ_ECA0 to BluetoothGattCharacteristic.PROPERTY_READ,
            LifeFitnessUuids.UUID_LF_B90_READ_EF5C to BluetoothGattCharacteristic.PROPERTY_READ,
            LifeFitnessUuids.UUID_LF_B90_WRITE_F4E8 to writeBoth,
            LifeFitnessUuids.UUID_LF_B90_WRITE_F164 to writeBoth,
            LifeFitnessUuids.UUID_LF_B90_WRITE_F330 to writeBoth,
            LifeFitnessUuids.UUID_LF_B90_WRITE_FB00 to writeBoth,
            LifeFitnessUuids.UUID_LF_B90_WRITE_FD94 to writeBoth,
            LifeFitnessUuids.UUID_LF_CONTROL_2 to BluetoothGattCharacteristic.PROPERTY_WRITE,
            LifeFitnessUuids.UUID_LF_B90_WRITE_6AB6 to BluetoothGattCharacteristic.PROPERTY_WRITE,
        )
        assertEquals(expected, control.characteristics.associate { it.uuid to it.properties })
    }

    @Test
    fun `generated records use the captured active flag and length family`() {
        val sample = FtmsMockSample(
            elapsedSeconds = 42,
            heartRate = 131,
            caloriesTotal = 17,
            caloriesPerHour = 430,
            caloriesPerMinute = 7,
            speedKph = 18.25,
            averageSpeedKph = 11.5,
            distanceMeters = 250,
            cadence = 81.0,
            averageCadence = 79.0,
            resistance = 12,
            inclinationPercent = 3.5,
            powerWatts = 215,
            averagePowerWatts = 202,
            instantPaceMinPerKm = 3.4,
            averagePaceMinPerKm = 3.6,
            movementCount = 48,
            positiveElevationMeters = 7.0,
            floors = 6,
        )
        val expected = mapOf(
            LifeFitnessMachine.TREADMILL to (0x059E to 25),
            LifeFitnessMachine.CROSS_TRAINER to (0x2FBE to 34),
            LifeFitnessMachine.STAIR_CLIMBER to (0x017E to 20),
            LifeFitnessMachine.INDOOR_BIKE to (0x0BFA to 27),
        )

        expected.forEach { (machine, identity) ->
            val value = LifeFitnessPacketEncoder.encode(machine, sample)
            val width = if (machine == LifeFitnessMachine.CROSS_TRAINER) 3 else 2
            val flags = (0 until width).fold(0) { total, index ->
                total or ((value[index].toInt() and 0xFF) shl (8 * index))
            }
            assertEquals(identity.first, flags)
            assertEquals(identity.second, value.size)
        }

        val bike = LifeFitnessPacketEncoder.encode(LifeFitnessMachine.INDOOR_BIKE, sample)
        assertEquals(162, littleEndianU16(bike, 6))
        assertEquals(158, littleEndianU16(bike, 8))
        assertEquals(0, littleEndianU16(bike, 0) and 0x0004)

        val treadmill = LifeFitnessPacketEncoder.encode(LifeFitnessMachine.TREADMILL, sample)
        assertEquals(0x7FFF, littleEndianU16(treadmill, 11))
        assertEquals(0x7FFF, littleEndianU16(treadmill, 15))

        val energyOffsets = mapOf(
            LifeFitnessMachine.TREADMILL to 17,
            LifeFitnessMachine.CROSS_TRAINER to 26,
            LifeFitnessMachine.STAIR_CLIMBER to 12,
            LifeFitnessMachine.INDOOR_BIKE to 19,
        )
        energyOffsets.forEach { (machine, totalOffset) ->
            val value = LifeFitnessPacketEncoder.encode(machine, sample)
            assertEquals("$machine calories per hour", 0xFFFF, littleEndianU16(value, totalOffset + 2))
            assertEquals("$machine calories per minute", 0xFF, value[totalOffset + 4].toInt() and 0xFF)
        }
        val crossTrainer = LifeFitnessPacketEncoder.encode(LifeFitnessMachine.CROSS_TRAINER, sample)
        assertEquals(0xFFFF, littleEndianU16(crossTrainer, 18))
    }

    private data class PersonaIdentity(
        val machine: LifeFitnessMachine,
        val name: String,
        val serviceData: ByteArray,
    )

    private fun littleEndianU16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)

    private val writeBoth = BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
}

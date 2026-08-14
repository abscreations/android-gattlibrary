package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * x70 production plumbing — plan §7, §11 obligation 0.
 *
 * The §11 audit's question is not "does the parser work" but "can a user find this machine and get
 * data from it". Every defect the Phase 0 and Phase 5b audits found had the same shape: a family
 * known to some layers and not others. This file walks the layers.
 */
class SchwinnX70PlumbingTest {

    private val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)

    private fun inventory(vararg characteristics: UUID): Map<UUID, Set<UUID>> =
        mapOf(BluetoothIds.UUID_X70_SERVICE to characteristics.toSet())

    // ── Discovery: both console shapes, and neither invented ─────────────────────

    @Test
    fun `a 270 subscribes to its main stream only`() {
        val targets = planSchwinnX70Discovery(
            inventory(BluetoothIds.UUID_X70_MEASUREMENT, BluetoothIds.UUID_X70_CONTROL),
        )
        assertEquals(listOf(BluetoothIds.UUID_X70_MEASUREMENT), targets.map { it.characteristicUuid })
    }

    @Test
    fun `a 170 subscribes to both of its streams and not to a main stream it lacks`() {
        val targets = planSchwinnX70Discovery(
            inventory(
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
                BluetoothIds.UUID_X70_CONTROL,
            ),
        )
        assertEquals(
            listOf(BluetoothIds.UUID_X70_SUPPLEMENTAL, BluetoothIds.UUID_X70_SIBLING),
            targets.map { it.characteristicUuid },
        )
    }

    @Test
    fun `a console exposing all three streams gets all three`() {
        // The 270's own GATT database declares every characteristic even though it only notifies on
        // one — the captured nRF Connect log shows exactly that, and enabling notifications on a
        // characteristic that never fires costs nothing. Requiring the *inventory* to distinguish
        // the consoles would be inventing a distinction the GATT does not make.
        val targets = planSchwinnX70Discovery(
            inventory(
                BluetoothIds.UUID_X70_MEASUREMENT,
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
            ),
        )
        assertEquals(3, targets.size)
    }

    @Test
    fun `a service with no measurement stream activates nothing`() {
        // §0.7a: a service UUID is evidence of identity, not a promise of data. Declining leaves
        // the link free for any other handler that does match.
        assertTrue(planSchwinnX70Discovery(inventory(BluetoothIds.UUID_X70_CONTROL)).isEmpty())
        assertTrue(planSchwinnX70Discovery(emptyMap()).isEmpty())
        assertTrue(
            "a foreign service must not activate this handler",
            planSchwinnX70Discovery(mapOf(BluetoothIds.UUID_FTMS_SERVICE to setOf(BluetoothIds.UUID_X70_MEASUREMENT)))
                .isEmpty(),
        )
    }

    @Test
    fun `every planned target names the x70 service as its parent`() {
        // Queue targeting keys on the parent, so a target naming the wrong service would write a
        // CCCD on a characteristic belonging to someone else.
        val targets = planSchwinnX70Discovery(
            inventory(
                BluetoothIds.UUID_X70_MEASUREMENT,
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
            ),
        )
        assertTrue(targets.all { it.serviceUuid == BluetoothIds.UUID_X70_SERVICE })
    }

    // ── The initialization command (V17) ─────────────────────────────────────────

    @Test
    fun `the initialisation command is QZ's literal and satisfies both framing rules`() {
        // Byte 0 is the total length and every octet sums to 0x100. Asserted rather than computed:
        // a command FitBridge generated would be a third value for a field where TMIW and QZ
        // already disagree and nothing can choose between them — the V13 mistake.
        assertEquals(
            listOf(0x07, 0x01, 0xD3, 0x00, 0x1F, 0x05, 0x01),
            SCHWINN_X70_INITIALISATION.map { it.toInt() and 0xFF },
        )
        assertTrue(SchwinnX70Measurement.isWellFormedCommand(SCHWINN_X70_INITIALISATION))
        assertEquals(0x100, SCHWINN_X70_INITIALISATION.sumOf { it.toInt() and 0xFF })
        assertEquals(SCHWINN_X70_INITIALISATION.size, SCHWINN_X70_INITIALISATION[0].toInt() and 0xFF)
    }

    @Test
    fun `TMIW's command is also well formed, which is why V17 stays open`() {
        // Both are structurally valid, so the framing rule cannot arbitrate between them. Only QZ's
        // was observed being accepted by a physical console, which is the whole basis for the
        // choice — and it is a weak one, recorded as such.
        val tmiw = byteArrayOf(0x05, 0x03, 0xD9.toByte(), 0x00, 0x1F)
        assertTrue(SchwinnX70Measurement.isWellFormedCommand(tmiw))
        assertFalse(
            "the two commands genuinely differ",
            tmiw.contentEquals(SCHWINN_X70_INITIALISATION),
        )
    }

    // ── Handler registration and ownership ───────────────────────────────────────

    @Test
    fun `the handler is constructed on every machine connection`() {
        // D44's regression guard. A handler registered in the factory but absent from
        // MACHINE_PROTOCOLS is buildable and never built — which is exactly what was true of
        // Concept2 until 2026-08-12.
        assertTrue(ProtocolKey.SCHWINN_X70 in MACHINE_PROTOCOLS)

        val handlers = MACHINE_PROTOCOLS.map(factory::create)
        assertEquals(
            "exactly one x70 handler per machine connection",
            1,
            handlers.count { it is SchwinnX70ProtocolHandler },
        )
    }

    @Test
    fun `the handler activates on its service and owns all four characteristics`() {
        val active = activateMatchingProtocolHandlers(
            MACHINE_PROTOCOLS.map(factory::create),
            setOf(BluetoothIds.UUID_X70_SERVICE),
            offer = { true },
        )
        assertEquals(1, active.size)
        assertTrue(active.single() is SchwinnX70ProtocolHandler)

        val handler = active.single()
        assertEquals(setOf(BluetoothIds.UUID_X70_SERVICE), handler.primaryServices)
        assertEquals(
            setOf(
                BluetoothIds.UUID_X70_MEASUREMENT,
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
                BluetoothIds.UUID_X70_CONTROL,
            ),
            handler.extraCharacteristics,
        )
    }

    @Test
    fun `an x70 is not offered as a sensor`() {
        // A machine protocol, like FTMS, Concept2 and Precor. Appearing in the secondary-sensor
        // path would let a user assign a whole exercise bike to a heart-rate slot.
        assertTrue(
            "no service type may map to the x70 handler",
            BtEngineState.BtServiceType.entries.none {
                sensorProtocolFor(it) == ProtocolKey.SCHWINN_X70
            },
        )
    }

    // ── Control: none, and none reachable (D26, §9.2) ────────────────────────────

    @Test
    fun `no FTMS control point is reachable through the x70 handler`() {
        // The `1717b3c0-…` write is setup — it starts a notification stream — and is deliberately
        // not a control seam. If the FTMS control point ever appeared in this handler's owned
        // characteristics, a downstream target request could route to a machine that has no way to
        // honour it and no capture showing what would happen if it tried.
        val handler = factory.create(ProtocolKey.SCHWINN_X70)
        assertFalse(
            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT in handler.extraCharacteristics,
        )
        assertFalse(
            BluetoothIds.UUID_FTMS_SERVICE in handler.primaryServices,
        )
    }

    // ── Adapter policy ───────────────────────────────────────────────────────────

    @Test
    fun `the x70 adapter is a peer of the other proprietary adapters and needs no setup gate`() {
        assertEquals(
            "an x70 never competes with FTMS, so it ranks with the other vendor adapters",
            AdapterLivenessCoordinator.RANK_PRECOR,
            AdapterLivenessCoordinator.RANK_SCHWINN_X70,
        )
    }
}

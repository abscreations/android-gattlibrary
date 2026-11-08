package com.gmail.absolutebollockscreations.gattlibrary.central

/** Protocol implementations a host elects to register in one engine. */
class HandlerSet private constructor(private val selected: Set<Protocol>) {
    enum class Protocol {
        FTMS,
        HRM,
        RSC,
        CSC,
        CPS,
        CONCEPT2,
        PRECOR,
        SCHWINN_X70,
        FEC,
        WAHOO_KICKR,
        RIDEON,
        MAXPRO,
        FLYWHEEL,
        KAYAK_FIRST,
        KEISER_M_SERIES,
    }

    internal fun includes(key: ProtocolKey): Boolean = when (key) {
        ProtocolKey.FTMS -> Protocol.FTMS in selected
        ProtocolKey.HRM -> Protocol.HRM in selected
        ProtocolKey.RSC -> Protocol.RSC in selected
        ProtocolKey.CSC -> Protocol.CSC in selected
        ProtocolKey.CPS -> Protocol.CPS in selected
        ProtocolKey.CONCEPT2 -> Protocol.CONCEPT2 in selected
        ProtocolKey.PRECOR -> Protocol.PRECOR in selected
        ProtocolKey.SCHWINN_X70 -> Protocol.SCHWINN_X70 in selected
        ProtocolKey.FEC -> Protocol.FEC in selected
        ProtocolKey.WAHOO_KICKR -> Protocol.WAHOO_KICKR in selected
        ProtocolKey.RIDEON -> Protocol.RIDEON in selected
        ProtocolKey.MAXPRO -> Protocol.MAXPRO in selected
        ProtocolKey.FLYWHEEL -> Protocol.FLYWHEEL in selected
        ProtocolKey.KAYAK_FIRST -> Protocol.KAYAK_FIRST in selected
        else -> false
    }

    internal fun includesAdvertisementMachine(): Boolean = Protocol.KEISER_M_SERIES in selected

    internal fun includesSensor(service: BtEngineState.BtServiceType): Boolean =
        sensorProtocolFor(service)?.let(::includes) == true

    override fun equals(other: Any?): Boolean = other is HandlerSet && selected == other.selected

    override fun hashCode(): Int = selected.hashCode()

    companion object {
        fun all(): HandlerSet = HandlerSet(Protocol.entries.toSet())

        fun ftmsAndHrm(): HandlerSet = of(Protocol.FTMS, Protocol.HRM)

        fun of(vararg protocols: Protocol): HandlerSet = HandlerSet(protocols.toSet())
    }
}

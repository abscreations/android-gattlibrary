package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * Pure, testable plan for the legacy advertising and scan-response sets.
 *
 * Legacy advertisements have a hard 31-octet budget and Android reports overflow
 * only through an asynchronous callback. Computing the complete AD structure
 * sizes here lets startup fail deterministically before touching the radio and
 * keeps payload policy out of Android callback code.
 *
 * The previous FitBridge advertiser tracked the budget inline while building the
 * AdvertiseData and simply skipped structures once it ran out, so an overlong
 * persona name silently dropped the FTMS Service Data a scanner needs to type
 * the machine.
 */
data class FtmsAdvertisingPlan(
    val localName: String,
    val serviceData: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
    val serviceUuids: List<UUID> = listOf(FtmsUuids.UUID_FTMS_SERVICE),
) {
    val appearanceSupported: Boolean = false

    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES) {
            "Advertising payload is $advertisingBytes of $MAX_LEGACY_BYTES octets"
        }
        require(scanResponseBytes <= MAX_LEGACY_BYTES) {
            "Scan response is $scanResponseBytes of $MAX_LEGACY_BYTES octets"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FtmsAdvertisingPlan &&
            other.localName == localName &&
            other.serviceData.contentEquals(serviceData) &&
            other.advertisingBytes == advertisingBytes &&
            other.scanResponseBytes == scanResponseBytes &&
            other.serviceUuids == serviceUuids

    override fun hashCode(): Int {
        var result = localName.hashCode()
        result = 31 * result + serviceData.contentHashCode()
        result = 31 * result + advertisingBytes
        result = 31 * result + scanResponseBytes
        result = 31 * result + serviceUuids.hashCode()
        return result
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        private const val CONTROLLER_FLAGS_BYTES = 3
        private const val AD_HEADER_BYTES = 2
        private const val UUID_16_BYTES = 2

        /**
         * @param advertisedMachineType machine type written into the FTMS Service Data. Equals
         * [profile] except for the simulator's deliberately misadvertising test persona; the
         * local name and service list always follow [profile].
         */
        fun forProfile(
            profile: FtmsMachineProfile,
            includeNativeBikingSensors: Boolean = false,
            advertisedMachineType: FtmsMachineProfile = profile,
            localName: String = profile.advertisedName,
        ): FtmsAdvertisingPlan {
            val name = localName
            val serviceData = ftmsServiceData(advertisedMachineType, available = true)
            val serviceUuids = serviceUuidsFor(profile, includeNativeBikingSensors)
            val used = fixedBytes(serviceUuids, serviceData) +
                (AD_HEADER_BYTES + name.toByteArray(Charsets.UTF_8).size)
            return FtmsAdvertisingPlan(
                localName = name,
                serviceData = serviceData,
                advertisingBytes = used,
                scanResponseBytes = 0,
                serviceUuids = serviceUuids,
            )
        }

        /** Octets left for the local name once everything else in this persona's advertisement is counted. */
        fun localNameBudget(
            profile: FtmsMachineProfile,
            includeNativeBikingSensors: Boolean = false,
            advertisedMachineType: FtmsMachineProfile = profile,
        ): Int {
            val serviceData = ftmsServiceData(advertisedMachineType, available = true)
            val uuids = serviceUuidsFor(profile, includeNativeBikingSensors)
            return MAX_LEGACY_BYTES - fixedBytes(uuids, serviceData) - AD_HEADER_BYTES
        }

        /**
         * The name a bridge session advertises (LIB-18e): [BtServiceProfiles.DEVICE_NAME_PREFIX]
         * followed by the bridged machine's own name, cut to this persona's octet budget.
         *
         * The name is cleaned first (control characters become spaces, whitespace collapses, ends trimmed)
         * and truncated on a code-point boundary by UTF-8 octets, never by characters, so a
         * multi-byte name cannot overflow the advertisement or be split mid-character. With no
         * usable machine name (unknown, blank, or a simulator persona) the persona's fallback
         * name, for example "FB-Treadmill", is used unchanged.
         */
        fun bridgeLocalName(
            profile: FtmsMachineProfile,
            upstreamName: String?,
            includeNativeBikingSensors: Boolean = false,
            advertisedMachineType: FtmsMachineProfile = profile,
        ): String {
            val cleaned = cleanMachineName(upstreamName) ?: return profile.advertisedName
            val prefix = BtServiceProfiles.DEVICE_NAME_PREFIX
            val available = localNameBudget(profile, includeNativeBikingSensors, advertisedMachineType) -
                prefix.toByteArray(Charsets.UTF_8).size
            val truncated = truncateUtf8(cleaned, available).trimEnd()
            return if (truncated.isEmpty()) profile.advertisedName else prefix + truncated
        }

        internal fun cleanMachineName(name: String?): String? =
            name
                ?.map { if (Character.isISOControl(it)) ' ' else it }
                ?.joinToString("")
                ?.replace(WHITESPACE_RUN, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        /** Longest prefix of [text] whose UTF-8 encoding fits [maxBytes], ending on a code point. */
        internal fun truncateUtf8(text: String, maxBytes: Int): String {
            if (maxBytes <= 0) return ""
            var used = 0
            var end = 0
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val width = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (used + width > maxBytes) break
                used += width
                end += Character.charCount(codePoint)
            }
            return text.substring(0, end)
        }

        private fun serviceUuidsFor(
            profile: FtmsMachineProfile,
            includeNativeBikingSensors: Boolean,
        ): List<UUID> = if (includeNativeBikingSensors && profile == FtmsMachineProfile.INDOOR_BIKE) {
            listOf(
                FtmsUuids.UUID_FTMS_SERVICE,
                SigUuids.UUID_CSC_SERVICE,
                SigUuids.UUID_CYCLING_POWER_SERVICE,
            )
        } else {
            listOf(FtmsUuids.UUID_FTMS_SERVICE)
        }

        // Android/controller contributes the Flags structure. Every other
        // structure needs its two-byte length/type header included here.
        private fun fixedBytes(serviceUuids: List<UUID>, serviceData: ByteArray): Int =
            CONTROLLER_FLAGS_BYTES +
                (AD_HEADER_BYTES + UUID_16_BYTES * serviceUuids.size) +
                (AD_HEADER_BYTES + UUID_16_BYTES + serviceData.size)

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}

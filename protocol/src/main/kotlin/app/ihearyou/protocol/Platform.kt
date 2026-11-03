package app.ihearyou.protocol

/**
 * GN handle-protocol generations, chosen from the GN Version characteristic: protocolVersion,
 * and for protocol 5 also handleListVersion (0 = 5.0, otherwise 5.1).
 *
 * Only the handles that differ between generations are listed; the core set (1 battery, 2 side,
 * 5 microphone attenuation, 7/8 programs, 10/12 program names, 21 stream status) is shared.
 * On protocols 1 to 3, handle 53 is StartDFU, so nothing here may assume the protocol 4+ map.
 */
enum class Platform(
    val label: String,
    val pushButton: Int?,
    val startDfu: Int,
    val hiState: Int?,
    val tapConfig: Int?,
    /** Handle 36 carries the per-program standard-feature blob only from protocol 4 on. */
    val standardFeatures: Boolean,
) {
    V1("GN protocol 1", pushButton = null, startDfu = 53, hiState = null, tapConfig = null, standardFeatures = false),
    V2("GN protocol 2", pushButton = 57, startDfu = 53, hiState = 54, tapConfig = null, standardFeatures = false),
    V3("GN protocol 3", pushButton = 57, startDfu = 53, hiState = 54, tapConfig = null, standardFeatures = false),
    V4("GN protocol 4", pushButton = 53, startDfu = 49, hiState = 50, tapConfig = null, standardFeatures = true),
    V5_0("GN protocol 5.0", pushButton = 53, startDfu = 49, hiState = 50, tapConfig = 66, standardFeatures = true),
    V5_1("GN protocol 5.1", pushButton = 53, startDfu = 49, hiState = 50, tapConfig = 66, standardFeatures = true),
    ;

    /**
     * The only handles the app ever writes: microphone attenuation, active program, program-name
     * selector and (where it exists) the tap configuration. Everything else is read-only, so no
     * firmware, DFU or fitting handle can be reached through [app.ihearyou.protocol] users.
     */
    val writable: Set<Int>
        get() = setOfNotNull(Handles.MIC_ATTENUATION, Handles.ACTIVE_PROGRAM, Handles.PROGRAM_NAME_SELECTOR, tapConfig)

    companion object {
        /** Null for generations the app does not know; callers must then stay read-only. */
        fun of(protocolVersion: Int, handleListVersion: Int): Platform? = when (protocolVersion) {
            1 -> V1
            2 -> V2
            3 -> V3
            4 -> V4
            5 -> if (handleListVersion != 0) V5_1 else V5_0
            else -> null
        }
    }
}

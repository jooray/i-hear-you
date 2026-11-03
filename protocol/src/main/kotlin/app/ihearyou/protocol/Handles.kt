package app.ihearyou.protocol

import java.util.UUID

object Gn {
    val SERVICE: UUID = UUID.fromString("0000fefe-0000-1000-8000-00805f9b34fb")
    val VERSION: UUID = UUID.fromString("97C1C193-EA53-4312-9BD9-E52207D5E03D")
    val SEC_CAP: UUID = UUID.fromString("12257119-DDCB-4A12-9A08-1CD4DF7921BB")
    val CHALLENGE: UUID = UUID.fromString("ADD69BFC-EDC7-40A4-BA5E-5F0107C3B3AC")
    val PUBLIC_KEY: UUID = UUID.fromString("98E3949E-D4DD-421C-87B2-5A5DDC1AC26F")
    val PASSCODE_ATTEMPTS: UUID = UUID.fromString("6D27FE99-0BFC-4C5E-9A3F-A4A271BB3D2A")
    val COMMAND: UUID = UUID.fromString("1959A468-3234-4C18-9E78-8DAF8D9DBF61")
    val NOTIFY: UUID = UUID.fromString("8B51A2CA-5BED-418B-B54B-22FE666AADD2")
    val FEATURE_SUPPORT: UUID = UUID.fromString("650C3A00-CB6D-467D-A20B-3544F189D8AF")
    val FIT_PROTOCOL_CONFIG: UUID = UUID.fromString("E09369EC-150B-40B0-ABD5-841CA383D7FA")

    val LEA_SERVICE: UUID = UUID.fromString("7D74F4BD-C74A-4431-862C-CCE884371592")
    val BATTERY: UUID = UUID.fromString("24E1DFF3-AE90-41BF-BFBD-2CF8DF42BF87")
    val SIDE: UUID = UUID.fromString("8D17AC2F-1D54-4742-A49A-EF4B20784EB3")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val DIS_SERVICE: UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
    val DIS_MANUFACTURER: UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL: UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")
    val DIS_FIRMWARE: UUID = UUID.fromString("00002A26-0000-1000-8000-00805f9b34fb")
    val DIS_HARDWARE: UUID = UUID.fromString("00002A27-0000-1000-8000-00805f9b34fb")
    val DIS_SERIAL: UUID = UUID.fromString("00002A25-0000-1000-8000-00805f9b34fb")
}

/** GN protocol 5.1 property handles. Handle 0 = not handle-controlled (use the UUID directly). */
object Handles {
    const val BATTERY = 1
    const val SIDE = 2
    const val OWN_ID = 3
    const val OTHER_ID = 4
    const val MIC_ATTENUATION = 5
    const val STREAMER_ATTENUATION = 6
    const val AVAILABLE_PROGRAMS = 7
    const val ACTIVE_PROGRAM = 8
    const val PROGRAMS_VERSION = 9
    const val PROGRAM_NAME = 10
    const val PROGRAM_CATEGORY = 11
    const val PROGRAM_NAME_SELECTOR = 12
    const val PRODUCT_ID = 13
    /** 32-bit little-endian device identifier; bits 24..27 carry the price tier. Readable without a session key. */
    const val DEVICE_ID = 42
    const val DEVICE_OPTIONS = 14
    const val ATTENUATION_DEFAULTS = 16
    const val ENVIRONMENT_ID = 17
    const val INPUT_MODE = 18
    const val CRITICAL_ALGO = 19
    const val SECURITY_STATUS = 20
    const val STREAM_STATUS = 21
    const val MELODY = 22
    const val LED = 23
    const val EAR_PIECE = 24
    const val FITTED_PROGRAMS = 25
    const val LAST_FITTED_ID = 26
    const val GAIN_DATA = 27
    const val ALL_GAIN_DATA = 28
    const val STREAM_TYPE = 31
    const val STANDARD_FEATURES = 35
    const val ALL_STANDARD_FEATURES = 36
    const val RIE_DETECT = 37
    const val ACCESSORY_WHITELIST = 38
    const val REMOTE_CONTROL = 39
    const val START_DFU = 49
    const val HI_STATE = 50
    const val ALL_VOLUMES = 51
    const val PUSH_BUTTON = 53
    const val DEVICE_NAME = 60
    const val TAP_CONFIG = 66
    const val ACTIVE_STREAM_PROGRAM = 67
    const val BONDED_BT_ADDRESS = 68
    const val BONDED_SERIAL = 69
    const val AUTOMATIC_STREAMING = 70
    const val MEDIA_TYPE = 72
    const val AURACAST_CONTROL = 73
    const val START_FITTING_AUTH_WINDOW = 74

    val names: Map<Int, String> = mapOf(
        BATTERY to "Battery level",
        SIDE to "Hearing instrument side",
        OWN_ID to "Own identification",
        OTHER_ID to "Other identification",
        MIC_ATTENUATION to "Microphone attenuation",
        STREAMER_ATTENUATION to "Streamer attenuation",
        AVAILABLE_PROGRAMS to "Available programs",
        ACTIVE_PROGRAM to "Current active program",
        PROGRAMS_VERSION to "Available programs version",
        PROGRAM_NAME to "Program name",
        PROGRAM_CATEGORY to "Program category",
        PROGRAM_NAME_SELECTOR to "Program name selector",
        PRODUCT_ID to "Product ID",
        DEVICE_ID to "Device ID",
        DEVICE_OPTIONS to "Device information options",
        ATTENUATION_DEFAULTS to "Attenuation defaults",
        ENVIRONMENT_ID to "Program environment ID",
        INPUT_MODE to "Input mode",
        CRITICAL_ALGO to "Critical algorithm status",
        SECURITY_STATUS to "Security status",
        STREAM_STATUS to "Stream status",
        MELODY to "Melody",
        LED to "LED",
        EAR_PIECE to "Ear piece configuration",
        FITTED_PROGRAMS to "Fitted programs",
        LAST_FITTED_ID to "Last fitted ID",
        GAIN_DATA to "Gain data",
        ALL_GAIN_DATA to "All gain data",
        STREAM_TYPE to "Stream type",
        STANDARD_FEATURES to "Standard features",
        ALL_STANDARD_FEATURES to "All standard features",
        RIE_DETECT to "RIE detection data",
        ACCESSORY_WHITELIST to "Accessory whitelist",
        REMOTE_CONTROL to "Remote control",
        START_DFU to "Start DFU",
        HI_STATE to "HI state",
        ALL_VOLUMES to "All volumes",
        PUSH_BUTTON to "Push button",
        DEVICE_NAME to "Device name",
        TAP_CONFIG to "Accelerometer tap config",
        ACTIVE_STREAM_PROGRAM to "Current active stream program",
        BONDED_BT_ADDRESS to "Bonded BT address",
        BONDED_SERIAL to "Bonded serial number",
        AUTOMATIC_STREAMING to "Automatic streaming",
        MEDIA_TYPE to "Media type",
        AURACAST_CONTROL to "Auracast control point",
        START_FITTING_AUTH_WINDOW to "Start fitting authentication window",
    )
}

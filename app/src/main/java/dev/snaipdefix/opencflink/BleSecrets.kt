package dev.snaipdefix.opencflink

/**
 * Per-phone-per-bike auth material, extracted via Frida from the official CFMoto app's
 * EncryptInfoBean (stored in MMKV under key "EncryptKey").
 *
 * TODO: replace with a runtime loader (read from official app's MMKV) so this works for
 * any paired phone/bike combination. For Stage 1 we hardcode the values that match the
 * user's specific pairing.
 */
object BleSecrets {

    /** EncryptInfoBean.getEncryptValue(), 64-byte token sent in BLE cmd 0x5A. */
    const val ENCRYPT_VALUE_HEX: String =
        "d4297222730003e6d1c16f8b783dac51" +
        "ed0023dc4dc305be0e24c60cbfbbb90b" +
        "1c516c883d7699865867fdd2ca84209f" +
        "b2c0f4a3fbb381803ea6eb9219ab287a"

    /** EncryptInfoBean.getKey(), 32 ASCII bytes used directly as AES-256 key. */
    const val KEY_ASCII: String = "9Nz5xdZoXD288Vzl7KgWCUTO7tjzVjFV"

    /** EncryptInfoBean.getIv(), 16 ASCII bytes; only used for CBC ops elsewhere in the SDK,
     * not for the ECB challenge/response. Kept for completeness. */
    const val IV_ASCII: String = "1aeJz7ZeBON7wMV6"

    /**
     * the bike's BLE MAC. The official app scans by MAC (ScanFilter.setDeviceAddress),
     * because it learned the MAC during initial pairing and stored it in MMKV.
     * if you know your bike's BLE MAC, set it here for a direct, robust connect.
     * Leave null to fall back to scan-by-name / scan-by-advertised-service-UUID.
     *
     * NOTE: this is the *Bluetooth* MAC, which is usually different from the Wi-Fi MAC
     * (6C:09:4A:0F:6C:F8) seen in the QR. Pull it from the official app's device list /
     * MMKV, or read it from the [BleWakeUp] scan log (every candidate is logged).
     */
    val BIKE_BLE_MAC: String? = null

    /** Substrings to match against the BLE advertised device name when MAC is unknown. */
    val NAME_HINTS = listOf("CFMOTO", "CFDL", "GUV", "CFMOTO-")

    val encryptValueBytes: ByteArray get() = BleProtocol.hexToBytes(ENCRYPT_VALUE_HEX)
    val keyBytes: ByteArray get() = KEY_ASCII.toByteArray(Charsets.US_ASCII)
    val ivBytes:  ByteArray get() = IV_ASCII.toByteArray(Charsets.US_ASCII)
}

package dev.snaipdefix.opencflink

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Wire frame + crypto helpers reverse-engineered from CFMoto's BleService.
 *
 * Frame: AB CD | cmd(1) | len_LE(2) | payload | sum8 | CF
 *   sum8 = ( cmd + len_lo + len_hi + Σ(payload) ) mod 256
 *
 * Payload is a tiny protobuf with a single bytes/string field (tag 0x0A).
 */
object BleProtocol {

    val SERVICE_UUID:    UUID = UUID.fromString("0000B354-D6D8-C7EC-BDF0-EAB1BFC6BCBC")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000B356-D6D8-C7EC-BDF0-EAB1BFC6BCBC")
    val NOTIFY_CHAR_UUID:UUID = UUID.fromString("0000B357-D6D8-C7EC-BDF0-EAB1BFC6BCBC")
    val CCCD_UUID:       UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val CMD_AUTH_PKG: Byte  = 0x5A.toByte()  // phone → bike: encryptValue blob
    const val CMD_CHALLENGE: Byte = 0x5B.toByte()  // bike → phone: AES-encrypted 16 bytes
    const val CMD_RESPONSE: Byte  = 0x5C.toByte()  // phone → bike: decrypted 8-digit plaintext
    const val CMD_ACK: Byte       = 0x5D.toByte()  // bike → phone: status=0 OK

    private const val MAGIC_HI: Byte = 0xAB.toByte()
    private const val MAGIC_LO: Byte = 0xCD.toByte()
    private const val FRAME_END: Byte = 0xCF.toByte()

    /** Encode a full frame with one protobuf bytes/string field (tag 0x0A). */
    fun encodeFrame(cmd: Byte, protoBytesField1: ByteArray): ByteArray {
        val payload = wrapProtoBytes1(protoBytesField1)
        val len = payload.size
        require(len <= 0xFFFF) { "payload too long: $len" }
        val frame = ByteArray(7 + len)
        frame[0] = MAGIC_HI
        frame[1] = MAGIC_LO
        frame[2] = cmd
        frame[3] = (len and 0xFF).toByte()
        frame[4] = ((len shr 8) and 0xFF).toByte()
        payload.copyInto(frame, 5)
        var sum = (cmd.toInt() and 0xFF) + (frame[3].toInt() and 0xFF) + (frame[4].toInt() and 0xFF)
        for (b in payload) sum += b.toInt() and 0xFF
        frame[len + 5] = (sum and 0xFF).toByte()
        frame[len + 6] = FRAME_END
        return frame
    }

    /** Parse one frame, returns (cmd, raw protobuf payload) or null if framing/CRC fail. */
    fun decodeFrame(raw: ByteArray): Pair<Byte, ByteArray>? {
        if (raw.size < 7) return null
        if (raw[0] != MAGIC_HI || raw[1] != MAGIC_LO) return null
        val cmd = raw[2]
        val len = (raw[3].toInt() and 0xFF) or ((raw[4].toInt() and 0xFF) shl 8)
        if (raw.size < 7 + len) return null
        if (raw[5 + len + 1] != FRAME_END) return null
        var sum = (cmd.toInt() and 0xFF) + (raw[3].toInt() and 0xFF) + (raw[4].toInt() and 0xFF)
        for (i in 0 until len) sum += raw[5 + i].toInt() and 0xFF
        if ((sum and 0xFF).toByte() != raw[5 + len]) return null
        return cmd to raw.copyOfRange(5, 5 + len)
    }

    /** Pull the bytes/string out of a single-field protobuf payload (field 1, wire type 2). */
    fun unwrapProtoBytes1(proto: ByteArray): ByteArray? {
        if (proto.isEmpty() || proto[0] != 0x0A.toByte()) return null
        // read varint length
        var idx = 1
        var len = 0
        var shift = 0
        while (idx < proto.size) {
            val b = proto[idx].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift)
            idx++
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 28) return null
        }
        if (idx + len > proto.size) return null
        return proto.copyOfRange(idx, idx + len)
    }

    /** build a single-field protobuf payload: tag 0x0A + varint length + bytes. */
    private fun wrapProtoBytes1(data: ByteArray): ByteArray {
        val lenVarint = encodeVarint(data.size)
        val out = ByteArray(1 + lenVarint.size + data.size)
        out[0] = 0x0A
        lenVarint.copyInto(out, 1)
        data.copyInto(out, 1 + lenVarint.size)
        return out
    }

    private fun encodeVarint(v: Int): ByteArray {
        var value = v
        val bytes = ArrayList<Byte>(4)
        while (true) {
            if ((value and 0x7F.inv()) == 0) {
                bytes.add(value.toByte())
                break
            }
            bytes.add(((value and 0x7F) or 0x80).toByte())
            value = value ushr 7
        }
        return bytes.toByteArray()
    }

    /** AES-256-ECB-PKCS7 decrypt with the EncryptInfoBean.key bytes as the AES key. */
    fun aesEcbDecrypt(ciphertext: ByteArray, keyBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return cipher.doFinal(ciphertext)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((clean[2 * i].digitToInt(16) shl 4) or clean[2 * i + 1].digitToInt(16)).toByte()
        }
        return out
    }

    fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

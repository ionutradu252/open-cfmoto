package dev.snaipdefix.opencflink

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Mirrors net.easyconn.carman.utils.RSAUtil.
 *
 * The Carbit SDK generates a 1024-bit RSA keypair locally on first run.
 *   - The bike encrypts a HUID with our public key  → we decrypt with private key (decryptByPrivateKey)
 *   - The bike sends `huid` cleartext in CLIENT_INFO → we sign by encrypting with the private key
 *     (encryptHUID), bike verifies with our public key.
 *
 * Single in-memory keypair for the session — that's enough for a PoC.
 */
object RsaKeys {
    private val keypair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(1024) }
        .generateKeyPair()

    val publicKey: PublicKey = keypair.public
    val privateKey: PrivateKey = keypair.private

    /** Base64-encoded X.509 public key (same format the SDK puts in CLIENT_INFO.pubkey). */
    val publicKeyBase64: String = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    /** "Encrypt" HUID with private key (= signing) — the bike will decrypt with our pubkey. */
    fun signHuid(huid: String): String {
        val priv = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKey.encoded))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, priv)
        return Base64.encodeToString(cipher.doFinal(huid.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Decrypt with our private key. Bike encrypted the input with our pubkey. */
    fun decryptWithPrivateKey(encrypted: ByteArray): ByteArray {
        val priv = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(privateKey.encoded))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, priv)
        return cipher.doFinal(encrypted)
    }
}

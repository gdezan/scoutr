package dev.scoutr.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM ciphertext plus the fresh IV the cipher generated for that write. */
data class EncryptedValue(
    val ciphertext: ByteArray,
    val iv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedValue) return false
        return ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}

/**
 * Encryption seam owned by [ConnectionStore]. Production uses Android Keystore;
 * tests inject a fake so JVM/Robolectric runs never touch device key material.
 */
interface ConnectionCipher {
    fun encrypt(plaintext: ByteArray): EncryptedValue

    fun decrypt(value: EncryptedValue): ByteArray

    /** Deletes the key so previously written ciphertext becomes unrecoverable. */
    fun clearKey()
}

/**
 * AES-256/GCM backed by a key that never leaves `AndroidKeyStore`. No user-auth
 * requirement: a push wake-up must decrypt the pairing while the device is locked.
 */
class AndroidKeystoreConnectionCipher(
    private val alias: String = DEFAULT_ALIAS,
) : ConnectionCipher {

    override fun encrypt(plaintext: ByteArray): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No caller-supplied IV: the provider generates a fresh one per write.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedValue(ciphertext = cipher.doFinal(plaintext), iv = cipher.iv)
    }

    override fun decrypt(value: EncryptedValue): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = existingKey() ?: throw IllegalStateException("Keystore alias $alias is gone")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, value.iv))
        return cipher.doFinal(value.ciphertext)
    }

    override fun clearKey() {
        keyStore().deleteEntry(alias)
    }

    private fun secretKey(): SecretKey = existingKey() ?: generateKey()

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val DEFAULT_ALIAS = "scoutr_connection_token"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}

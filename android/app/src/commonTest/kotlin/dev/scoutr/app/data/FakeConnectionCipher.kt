package dev.scoutr.app.data

/**
 * Reversible stand-in for [AndroidKeystoreConnectionCipher]: Robolectric has no
 * `AndroidKeyStore` provider, so JVM tests inject this instead. The transform
 * depends only on the persisted IV, so separate instances read each other's
 * ciphertext exactly like one Keystore key would.
 */
class FakeConnectionCipher : ConnectionCipher {
    /** Simulates a Keystore that cannot produce a key for this write. */
    var failEncrypt = false

    /** Cleared keys make previously written ciphertext undecryptable. */
    var keyPresent = true
    var clearKeyCalls = 0
        private set

    private var ivCounter = 0

    override fun encrypt(plaintext: ByteArray): EncryptedValue {
        if (failEncrypt) throw IllegalStateException("keystore unavailable")
        keyPresent = true
        val iv = byteArrayOf((ivCounter++ % 9 + 1).toByte(), MARKER, MARKER)
        return EncryptedValue(
            ciphertext = plaintext.map { (it + iv[0]).toByte() }.toByteArray(),
            iv = iv,
        )
    }

    override fun decrypt(value: EncryptedValue): ByteArray {
        if (!keyPresent) throw IllegalStateException("keystore alias is gone")
        if (value.iv.size != IV_SIZE || value.iv[1] != MARKER) {
            throw IllegalStateException("corrupt iv")
        }
        return value.ciphertext.map { (it - value.iv[0]).toByte() }.toByteArray()
    }

    override fun clearKey() {
        clearKeyCalls++
        keyPresent = false
    }

    private companion object {
        const val IV_SIZE = 3
        const val MARKER: Byte = 7
    }
}

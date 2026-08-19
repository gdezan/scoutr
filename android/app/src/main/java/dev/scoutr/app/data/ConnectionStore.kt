package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log

/**
 * Persists the bridge host + pairing token.
 *
 * The host and its exposure kind are ordinary preferences; the bearer token is
 * only ever stored as AES-GCM ciphertext + IV produced by [ConnectionCipher].
 * Callers see plain [Saved] values and learn nothing about the encryption.
 */
class ConnectionStore(
    context: Context,
    private val cipher: ConnectionCipher = AndroidKeystoreConnectionCipher(),
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Saved(
        val host: String,
        val token: String,
        /** Provider metadata only: no client branches on it. */
        val exposure: ExposureKind,
    )

    val saved: Saved?
        get() {
            val host = prefs.getString(KEY_HOST, null)?.trim() ?: return null
            if (host.isBlank()) return null
            val token = readToken() ?: return null
            return Saved(
                host = host,
                token = token,
                // A pairing saved before exposure existed, or an unreadable
                // spelling, reads as Custom: an unlabelled base URL.
                exposure = ExposureKind.fromWire(prefs.getString(KEY_EXPOSURE, null))
                    ?: ExposureKind.Custom,
            )
        }

    /**
     * Persists the connection. Returns false when the token could not be
     * encrypted or written; in that case no plaintext token is left behind and
     * the pairing must be redone.
     *
     * [exposure] defaults to [ExposureKind.Custom]: a caller without pairing
     * metadata typed the base URL itself, and Scoutr knows nothing about what
     * fronts it.
     */
    fun save(
        host: String,
        token: String,
        exposure: ExposureKind = ExposureKind.Custom,
    ): Boolean {
        val encrypted = encryptOrNull(token.trim())
        val edit = prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putString(KEY_EXPOSURE, exposure.wire)
            .remove(KEY_LEGACY_TOKEN)
        if (encrypted == null) {
            // Fail closed: drop any stale credential rather than keep plaintext.
            edit.remove(KEY_TOKEN_CIPHERTEXT).remove(KEY_TOKEN_IV).commit()
            return false
        }
        edit.putEncryptedToken(encrypted)
        return edit.commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
        runCatching { cipher.clearKey() }
            .onFailure { Log.w(TAG, "Could not delete the connection key: ${it.javaClass.simpleName}") }
    }

    /**
     * Encrypted fields win. A legacy plaintext token is migrated in place; if
     * that migration fails it stays readable so the pairing is never stranded.
     */
    private fun readToken(): String? {
        val encrypted = storedEncryptedToken()
        if (encrypted != null) {
            return runCatching { String(cipher.decrypt(encrypted), Charsets.UTF_8) }
                .getOrElse {
                    // Corrupt ciphertext or a missing Keystore key: fail closed.
                    Log.w(TAG, "Saved pairing cannot be decrypted: ${it.javaClass.simpleName}")
                    null
                }
                ?.takeIf { it.isNotBlank() }
        }
        val legacy = prefs.getString(KEY_LEGACY_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        migrateLegacyToken(legacy)
        return legacy
    }

    /** One transaction: ciphertext + IV in, plaintext out. */
    private fun migrateLegacyToken(legacy: String) {
        val encrypted = encryptOrNull(legacy) ?: return
        val committed = prefs.edit()
            .putEncryptedToken(encrypted)
            .remove(KEY_LEGACY_TOKEN)
            .commit()
        if (!committed) {
            Log.w(TAG, "Token migration could not be committed; keeping the legacy value")
        }
    }

    private fun encryptOrNull(token: String): EncryptedValue? =
        runCatching { cipher.encrypt(token.toByteArray(Charsets.UTF_8)) }
            .getOrElse {
                Log.w(TAG, "Could not encrypt the connection token: ${it.javaClass.simpleName}")
                null
            }

    private fun storedEncryptedToken(): EncryptedValue? {
        val ciphertext = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)?.decodeOrNull() ?: return null
        val iv = prefs.getString(KEY_TOKEN_IV, null)?.decodeOrNull() ?: return null
        return EncryptedValue(ciphertext = ciphertext, iv = iv)
    }

    private fun SharedPreferences.Editor.putEncryptedToken(value: EncryptedValue) = this
        .putString(KEY_TOKEN_CIPHERTEXT, value.ciphertext.encode())
        .putString(KEY_TOKEN_IV, value.iv.encode())

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeOrNull(): ByteArray? =
        runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()

    internal companion object {
        const val FILE = "scoutr_connection"
        const val KEY_HOST = "host"

        /** Pre-Keystore plaintext token; only read during migration. */
        const val KEY_LEGACY_TOKEN = "token"
        const val KEY_TOKEN_CIPHERTEXT = "tokenCiphertext"
        const val KEY_TOKEN_IV = "tokenIv"
        const val KEY_EXPOSURE = "exposure"
        private const val TAG = "ConnectionStore"
    }
}

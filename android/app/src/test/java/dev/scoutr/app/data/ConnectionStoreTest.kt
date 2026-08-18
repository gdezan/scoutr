package dev.scoutr.app.data

import android.content.Context
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Token-at-rest contract: encrypted persistence, legacy plaintext migration
 * without a data-loss window, and fail-closed reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionStoreTest {

    private val cipher = FakeConnectionCipher()

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences(ConnectionStore.FILE, Context.MODE_PRIVATE)

    private fun store() = ConnectionStore(RuntimeEnvironment.getApplication(), cipher)

    @Before
    fun clearPrefs() {
        // Robolectric shares SharedPreferences across tests in one class.
        prefs().edit().clear().commit()
    }

    private fun seedLegacyPlaintext(host: String, token: String) {
        prefs().edit()
            .putString(ConnectionStore.KEY_HOST, host)
            .putString(ConnectionStore.KEY_LEGACY_TOKEN, token)
            .putString(ConnectionStore.KEY_NTFY_TOPIC, "scoutr_legacy")
            .commit()
    }

    @Test
    fun save_read_and_clear_round_trip() {
        assertTrue(store().save(" https://bridge.example.com ", " tok-123 ", "https://ntfy.example.com", "scoutr_abc"))

        val saved = store().saved
        assertNotNull(saved)
        assertEquals("https://bridge.example.com", saved!!.host)
        assertEquals("tok-123", saved.token)
        assertEquals("https://ntfy.example.com", saved.ntfyUrl)
        assertEquals("scoutr_abc", saved.ntfyTopic)
        assertEquals("an unlabelled save is a manual, custom base URL", ExposureKind.Custom, saved.exposure)

        store().clear()
        assertNull(store().saved)
        assertTrue("clear() must delete the dedicated key", cipher.clearKeyCalls > 0)
        assertTrue("no credential material may survive clear()", prefs().all.isEmpty())
    }

    @Test
    fun exposure_round_trips_through_its_stable_wire_spelling() {
        for (kind in ExposureKind.entries) {
            assertTrue(store().save("https://bridge.example.com", "tok-123", exposure = kind))

            assertEquals(kind, store().saved?.exposure)
            assertEquals(kind.wire, prefs().getString(ConnectionStore.KEY_EXPOSURE, null))
        }
        assertEquals("tailscale", ExposureKind.Tailscale.wire)
        assertEquals("cloudflare", ExposureKind.Cloudflare.wire)
        assertEquals("custom", ExposureKind.Custom.wire)
    }

    @Test
    fun a_pairing_without_a_readable_exposure_reads_as_custom() {
        // Saved before exposure existed, or written by a newer build.
        store().save("https://bridge.example.com", "tok-123", exposure = ExposureKind.Cloudflare)
        prefs().edit().putString(ConnectionStore.KEY_EXPOSURE, "wireguard").commit()
        assertEquals(ExposureKind.Custom, store().saved?.exposure)

        prefs().edit().remove(ConnectionStore.KEY_EXPOSURE).commit()
        assertEquals(ExposureKind.Custom, store().saved?.exposure)
    }

    @Test
    fun legacy_plaintext_pairing_keeps_its_token_and_reads_as_custom() {
        seedLegacyPlaintext("https://bridge.example.com", "legacy-token")

        val saved = store().saved

        assertEquals("legacy-token", saved?.token)
        assertEquals(ExposureKind.Custom, saved?.exposure)
    }

    @Test
    fun saved_preferences_never_contain_the_plaintext_token() {
        store().save("https://bridge.example.com", "tok-123")

        val all = prefs().all
        assertFalse("legacy plaintext key must be absent", all.containsKey(ConnectionStore.KEY_LEGACY_TOKEN))
        assertTrue(all.containsKey(ConnectionStore.KEY_TOKEN_CIPHERTEXT))
        assertTrue(all.containsKey(ConnectionStore.KEY_TOKEN_IV))
        assertTrue(
            "no preference value may contain the token",
            all.values.none { it is String && it.contains("tok-123") },
        )
        val stored = Base64.decode(all[ConnectionStore.KEY_TOKEN_CIPHERTEXT] as String, Base64.NO_WRAP)
        assertFalse(stored.contentEquals("tok-123".toByteArray()))
    }

    @Test
    fun each_write_persists_a_fresh_iv() {
        store().save("https://bridge.example.com", "tok-123")
        val firstIv = prefs().getString(ConnectionStore.KEY_TOKEN_IV, null)

        store().save("https://bridge.example.com", "tok-123")

        assertNotNull(firstIv)
        assertFalse(firstIv == prefs().getString(ConnectionStore.KEY_TOKEN_IV, null))
    }

    @Test
    fun legacy_plaintext_token_migrates_on_first_read() {
        seedLegacyPlaintext("https://bridge.example.com", "legacy-token")

        val saved = store().saved

        assertEquals("legacy-token", saved?.token)
        assertEquals("scoutr_legacy", saved?.ntfyTopic)
        assertFalse(prefs().all.containsKey(ConnectionStore.KEY_LEGACY_TOKEN))
        assertTrue(prefs().all.containsKey(ConnectionStore.KEY_TOKEN_CIPHERTEXT))
        // A fresh store reads the migrated pairing through the cipher alone.
        assertEquals("legacy-token", store().saved?.token)
    }

    @Test
    fun failed_migration_keeps_the_legacy_plaintext_readable() {
        seedLegacyPlaintext("https://bridge.example.com", "legacy-token")
        cipher.failEncrypt = true

        assertEquals("legacy-token", store().saved?.token)
        assertEquals("legacy-token", prefs().getString(ConnectionStore.KEY_LEGACY_TOKEN, null))
        assertFalse(prefs().all.containsKey(ConnectionStore.KEY_TOKEN_CIPHERTEXT))

        // Migration retries on the next read once encryption works again.
        cipher.failEncrypt = false
        assertEquals("legacy-token", store().saved?.token)
        assertFalse(prefs().all.containsKey(ConnectionStore.KEY_LEGACY_TOKEN))
    }

    @Test
    fun encrypted_fields_win_over_a_stale_plaintext_token() {
        store().save("https://bridge.example.com", "encrypted-token")
        prefs().edit().putString(ConnectionStore.KEY_LEGACY_TOKEN, "stale-token").commit()

        assertEquals("encrypted-token", store().saved?.token)
    }

    @Test
    fun corrupt_ciphertext_fails_closed() {
        store().save("https://bridge.example.com", "tok-123")
        prefs().edit()
            .putString(ConnectionStore.KEY_TOKEN_IV, Base64.encodeToString(byteArrayOf(1, 2), Base64.NO_WRAP))
            .commit()

        assertNull("a pairing that cannot be decrypted must not resolve", store().saved)
    }

    @Test
    fun missing_keystore_key_fails_closed_instead_of_inventing_plaintext() {
        store().save("https://bridge.example.com", "tok-123")
        cipher.clearKey()

        assertNull(store().saved)
    }

    @Test
    fun save_that_cannot_encrypt_reports_failure_and_writes_no_token() {
        cipher.failEncrypt = true

        assertFalse(store().save("https://bridge.example.com", "tok-123"))

        assertNull(store().saved)
        assertTrue(
            "no preference value may contain the token",
            prefs().all.values.none { it is String && it.contains("tok-123") },
        )
    }
}

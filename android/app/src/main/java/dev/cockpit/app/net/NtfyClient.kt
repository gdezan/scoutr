package dev.cockpit.app.net

import dev.cockpit.app.data.NtfyMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Polls a self-hosted ntfy topic for pushed messages.
 *
 * ntfy's `poll=1&since=<id>` returns the messages after the given id as
 * newline-delimited JSON objects. A `since` cursor makes each poll cheap and
 * resume-safe: a fresh app start gets the last message and misses nothing
 * after that point.
 */
class NtfyClient(
    private val okHttp: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Emits each new message as it arrives. Start with `since` = the id of the
     * last message already seen (empty for the very first poll).
     */
    fun messages(baseUrl: String, topic: String, initialSince: String? = null): Flow<NtfyMessage> =
        callbackFlow {
            // The caller owns the cursor (it loops to pick up future
            // messages), so this value is fixed for the call's lifetime.
            val since = initialSince
            val url = "${baseUrl.trimEnd('/')}/$topic/json?poll=1" +
                (if (since != null) "&since=$since" else "")
            val call = okHttp.newCall(
                Request.Builder().url(url.toHttpUrl()).build()
            )
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            close(IOException("ntfy ${it.code}: ${it.message}"))
                            return
                        }
                        val body = it.body?.string() ?: ""
                        for (line in body.lineSequence()) {
                            if (line.isBlank()) continue
                            try {
                                val message = json.decodeFromString(NtfyMessage.serializer(), line)
                                if (message.event == "message") {
                                    trySend(message)
                                }
                            } catch (_: Exception) {
                                // ntfy may interleave keepalives; skip unparseable lines.
                            }
                        }
                        // The poll returned; callers loop to pick up future messages.
                        close()
                    }
                }
            })
            awaitClose { call.cancel() }
        }

    /** One-shot poll used to seed the since cursor (or for tests). */
    suspend fun latestId(baseUrl: String, topic: String): String? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val url = "${baseUrl.trimEnd('/')}/$topic/json?poll=1"
            val call = okHttp.newCall(Request.Builder().url(url.toHttpUrl()).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        var last: String? = null
                        val body = it.body?.string() ?: ""
                        for (line in body.lineSequence()) {
                            if (line.isBlank()) continue
                            try {
                                val message = json.decodeFromString(NtfyMessage.serializer(), line)
                                last = message.id
                            } catch (_: Exception) {}
                        }
                        if (!continuation.isCancelled) continuation.resume(last)
                    }
                }
            })
        }
}

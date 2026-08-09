package dev.cockpit.app

import android.app.Application
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Minimal manual DI: the app container owns the singletons the view models need.
 * Tests replace [container] with fakes.
 */
class CockpitApp : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {

    val connectionStore = ConnectionStore(application)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val bridge = BridgeClient(
        okHttp = okHttp,
        connectionStore = connectionStore,
    )
}

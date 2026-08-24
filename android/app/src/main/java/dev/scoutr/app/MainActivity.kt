package dev.scoutr.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.scoutr.app.service.ScoutrDeepLink
import dev.scoutr.app.service.parseScoutrUri
import dev.scoutr.app.service.resolveCurrentNotificationLink
import dev.scoutr.app.state.ReduceMotionStore
import dev.scoutr.app.ui.nav.ScoutrAppNav
import dev.scoutr.app.ui.theme.ScoutrTheme

/**
 * Platform shell only: edge-to-edge setup, reduce-motion bootstrap, and
 * scoutr:// deep-link ingress. Everything navigation-related lives in
 * [ScoutrAppNav]; this class never sees a feature route or screen.
 */
class MainActivity : ComponentActivity() {

    /** Consumed by the NavHost: scoutr://chat/<paneId> links from notifications. */
    private val deepLink = mutableStateOf<ScoutrDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deepLink.value = validatedDeepLink(intent.dataString)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            // Mirror the system "Remove animations" setting into the theme so
            // every motion helper collapses to zero duration when asked.
            val context = LocalContext.current
            val motionStore = remember { ReduceMotionStore(context) }
            DisposableEffect(motionStore) {
                onDispose { motionStore.close() }
            }
            val reduceMotion by motionStore.reduceMotion.collectAsState()
            ScoutrTheme(reduceMotion = reduceMotion) {
                ScoutrAppNav(deepLink = deepLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = validatedDeepLink(intent.dataString)
    }

    /** External notification links must name the current host generation. */
    private fun validatedDeepLink(uri: String?) =
        parseScoutrUri(uri)?.let {
            resolveCurrentNotificationLink(
                it,
                ScoutrApp.container(this).hostRegistry,
                ScoutrApp.container(this).pushRegistrations::isRetiring,
            )
        }
}

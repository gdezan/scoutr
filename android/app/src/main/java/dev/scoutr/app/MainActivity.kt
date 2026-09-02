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
import dev.scoutr.app.service.ScoutrSubagentLink
import dev.scoutr.app.service.parseScoutrSubagentUri
import dev.scoutr.app.service.parseScoutrUri
import dev.scoutr.app.service.resolveCurrentNotificationLink
import dev.scoutr.app.service.resolveCurrentSubagentLink
import dev.scoutr.app.state.ReduceMotionStore
import dev.scoutr.app.update.PendingUpdateAction
import dev.scoutr.app.ui.nav.ScoutrAppNav
import dev.scoutr.app.ui.theme.ScoutrTheme

/**
 * Platform shell only: edge-to-edge setup, reduce-motion bootstrap, and
 * scoutr:// deep-link ingress. Everything navigation-related lives in
 * [ScoutrAppNav]; this class never sees a feature route or screen.
 */
class MainActivity : ComponentActivity() {

    /** Consumed by the NavHost: scoutr://chat/<hostId>/<generation>/<paneId> from notifications. */
    private val deepLink = mutableStateOf<ScoutrDeepLink?>(null)

    /** Consumed by the NavHost: scoutr://subagent/<hostId>/<generation>/<runId> from orphan notifications. */
    private val subagentLink = mutableStateOf<ScoutrSubagentLink?>(null)
    /**
     * Set by an update notification: go to Settings and either commit the
     * staged APK or resume a dropped transfer. Both need a foreground Activity,
     * which is exactly what routing them through here provides.
     *
     * A plain extra rather than a scoutr:// URI: [parseScoutrUri] is the chat
     * grammar, host-generation validated, and an update has no pane.
     */
    private val updateAction = mutableStateOf<PendingUpdateAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ingestDeepLink(intent.dataString)
        updateAction.value = updateActionFrom(intent)
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
                ScoutrAppNav(deepLink = deepLink, updateAction = updateAction, subagentLink = subagentLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestDeepLink(intent.dataString)
        updateAction.value = updateActionFrom(intent)
    }

    /** Unknown or absent names read as "no action" rather than crashing. */
    private fun updateActionFrom(intent: Intent): PendingUpdateAction? =
        intent.getStringExtra(EXTRA_UPDATE_ACTION)
            ?.let { name -> PendingUpdateAction.entries.firstOrNull { it.name == name } }

    companion object {
        /** Names a [PendingUpdateAction] when an update notification launched us. */
        const val EXTRA_UPDATE_ACTION = "scoutr.updateAction"
    }

    private fun ingestDeepLink(uri: String?) {
        deepLink.value = validatedDeepLink(uri)
        subagentLink.value = validatedSubagentLink(uri)
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

    private fun validatedSubagentLink(uri: String?) =
        parseScoutrSubagentUri(uri)?.let {
            resolveCurrentSubagentLink(
                it,
                ScoutrApp.container(this).hostRegistry,
                ScoutrApp.container(this).pushRegistrations::isRetiring,
            )
        }
}

package dev.scoutr.app.update

import dev.scoutr.app.data.ApkArtifact
import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostWorkCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The controller is what makes an update survive leaving Settings, so its job
 * is mostly about *who decides what, and when*: one job at a time, cancellation
 * that keeps the expensive bytes, and — the reason the split exists at all —
 * committing to the system install sheet only when the user is actually
 * looking at the update screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {

    @get:Rule
    val files = TemporaryFolder()

    private val bytes = "pretend this is an APK".toByteArray()

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun artifact() =
        ApkArtifact(size = bytes.size.toLong(), sha256 = sha256(bytes), commit = "abc1234", version = "0.4.0", versionCode = 12)

    private val binding = HostConnectionBinding(
        hostId = "host-1",
        connectionRevision = 1,
        baseUrl = "http://fake-bridge",
        token = "t",
        exposure = ExposureKind.Custom,
    )

    private class RecordingNotifier : UpdateNotifier {
        var ready: StagedIdentity? = null
        var failed: Pair<String, Boolean>? = null
        var cancelled = 0
        override fun showUpdateReady(identity: StagedIdentity) { ready = identity }
        override fun showUpdateFailed(message: String, resumable: Boolean) { failed = message to resumable }
        override fun cancelUpdateNotifications() { cancelled += 1 }
    }

    private class RecordingInstaller : ApkInstall {
        var installedBytes: ByteArray? = null
        override suspend fun install(apk: File) { installedBytes = apk.readBytes() }
    }

    private fun readyApi(): FakeScoutrApi = FakeScoutrApi().apply {
        apkBytes = bytes
        updateApkStatusResult = Result.success(
            UpdateApkStatusResponse(build = ApkBuild(state = "ready", buildId = 1, apk = artifact())),
        )
    }

    private fun controller(
        scope: TestScope,
        notifier: UpdateNotifier = RecordingNotifier(),
        installer: ApkInstall = RecordingInstaller(),
        staging: UpdateStaging = UpdateStaging(File(files.root, "update")),
        installedVersionCode: Int = 0,
    ): AppUpdateController {
        val work = HostWorkCoordinator().apply { activate(binding) }
        return AppUpdateController(
            scope = scope,
            work = work,
            notifications = notifier,
            staging = staging,
            installer = installer,
            installedVersionCode = installedVersionCode,
            pollDelayMs = 0,
        )
    }

    @Test
    fun `a completed download with the update screen visible commits straight to the install sheet`() = runTest {
        val installer = RecordingInstaller()
        val notifier = RecordingNotifier()
        val controller = controller(this, notifier, installer)
        controller.setUpdateScreenVisible(true)

        controller.start(readyApi(), binding)
        advanceUntilIdle()

        assertEquals(bytes.toList(), installer.installedBytes?.toList())
        assertNull("the shade must stay quiet while the user is watching", notifier.ready)
        assertEquals(UpdateState.Installing, controller.state.value)
    }

    @Test
    fun `a completed download with the screen hidden notifies instead of ambushing the user`() = runTest {
        val installer = RecordingInstaller()
        val notifier = RecordingNotifier()
        val controller = controller(this, notifier, installer)
        controller.setUpdateScreenVisible(false)

        controller.start(readyApi(), binding)
        advanceUntilIdle()

        // Committing here would throw a system install sheet over whatever the
        // user was actually doing — or be silently suppressed if backgrounded.
        assertNull(installer.installedBytes)
        assertEquals("0.4.0", notifier.ready?.version)
        assertTrue(controller.state.value is UpdateState.Ready)
    }

    @Test
    fun `a second start while one is running is a no-op, not a second build`() = runTest {
        val api = readyApi()
        api.gates["updateApkStatus"] = CompletableDeferred()
        val controller = controller(this)

        controller.start(api, binding)
        advanceUntilIdle()
        controller.start(api, binding)
        advanceUntilIdle()

        assertEquals(1, api.calls.count { it.name == "updateBuild" })

        api.gates["updateApkStatus"]?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `cancel returns to idle and keeps the bytes already downloaded`() = runTest {
        val api = readyApi()
        api.gates["updateApkStatus"] = CompletableDeferred()
        val staging = UpdateStaging(File(files.root, "update"))
        val controller = controller(this, staging = staging)
        staging.apkFile().writeBytes(bytes.copyOfRange(0, 6))

        controller.start(api, binding)
        advanceUntilIdle()
        controller.cancel()
        advanceUntilIdle()

        assertEquals(UpdateState.Idle, controller.state.value)
        // The whole point of cancelling rather than discarding: a later resume
        // must not pay for these bytes twice.
        assertEquals(6L, staging.partialBytes())

        api.gates["updateApkStatus"]?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a failed download is reported as resumable and keeps its partial bytes`() = runTest {
        val api = readyApi()
        api.downloadApkFailure = java.io.IOException("connection reset")
        val notifier = RecordingNotifier()
        val staging = UpdateStaging(File(files.root, "update"))
        val controller = controller(this, notifier, staging = staging)
        // What an interrupted transfer actually leaves behind: bytes plus the
        // sidecar that says which APK they belong to.
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes.copyOfRange(0, 6))

        controller.start(api, binding)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is UpdateState.Failed)
        assertTrue("a dropped transfer must offer a resume", (state as UpdateState.Failed).resumable)
        assertEquals("connection reset" to true, notifier.failed)
        assertEquals(6L, staging.partialBytes())
    }

    @Test
    fun `a build that never produced an APK fails without offering a resume`() = runTest {
        val api = FakeScoutrApi().apply {
            updateApkStatusResult = Result.success(
                UpdateApkStatusResponse(build = ApkBuild(state = "failed", buildId = 1, error = "gradle exploded")),
            )
        }
        val notifier = RecordingNotifier()
        val controller = controller(this, notifier)

        controller.start(api, binding)
        advanceUntilIdle()

        assertEquals("gradle exploded" to false, notifier.failed)
    }

    @Test
    fun `a complete staged APK from a previous process is offered for install`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val controller = controller(this, staging = staging)

        controller.rehydrate()

        assertEquals(UpdateState.Ready(staging.identity()!!), controller.state.value)
    }

    @Test
    fun `an unverified full-length staged APK is not offered for install`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        // Killed between the last byte and the checksum: full length, unproven.
        staging.apkFile().writeBytes(bytes)
        val controller = controller(this, staging = staging)

        controller.rehydrate()

        assertEquals(UpdateState.Idle, controller.state.value)
    }

    @Test
    fun `a partial staged APK is not mistaken for something installable`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes.copyOfRange(0, 6))
        val controller = controller(this, staging = staging)

        controller.rehydrate()

        assertEquals(UpdateState.Idle, controller.state.value)
        assertEquals("the partial must survive for a later resume", 6L, staging.partialBytes())
    }

    @Test
    fun `a declined install keeps the staged APK and stays one tap from retrying`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val installer = RecordingInstaller()
        val controller = controller(this, installer = installer, staging = staging)
        controller.rehydrate()

        controller.onInstallOutcome(ApkInstallOutcome.Failed("install cancelled"))

        // Ready, not Failed: the bytes are still good, so the row must keep
        // offering the install instead of another build and download.
        val state = controller.state.value
        assertTrue("a declined install must stay installable", state is UpdateState.Ready)
        assertEquals("install cancelled", (state as UpdateState.Ready).lastError)
        assertEquals(bytes.size.toLong(), staging.partialBytes())

        controller.install()
        advanceUntilIdle()
        assertEquals("retrying must not need a new download", bytes.toList(), installer.installedBytes?.toList())
    }

    @Test
    fun `host retirement discards staged bytes from a host no longer trusted`() = runTest {
        val api = readyApi()
        api.gates["updateApkStatus"] = CompletableDeferred()
        val staging = UpdateStaging(File(files.root, "update"))
        val work = HostWorkCoordinator().apply { activate(binding) }
        val controller = AppUpdateController(
            scope = this,
            work = work,
            notifications = RecordingNotifier(),
            staging = staging,
            installer = RecordingInstaller(),
            pollDelayMs = 0,
        )
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes.copyOfRange(0, 6))

        controller.start(api, binding)
        advanceUntilIdle()
        // Forget/re-pair retires the binding, which cancels the tracked job.
        work.retire(binding)
        advanceUntilIdle()

        assertEquals(UpdateState.Idle, controller.state.value)
        // Unlike a user cancel, these bytes came from a host that is no longer
        // trusted to update this app, so they must not survive to be resumed.
        assertEquals(0L, staging.partialBytes())

        api.gates["updateApkStatus"]?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a superseded run cannot stomp the state of the run that replaced it`() = runTest {
        val first = readyApi()
        first.gates["updateApkStatus"] = CompletableDeferred()
        val second = readyApi()
        second.gates["updateApkStatus"] = CompletableDeferred()
        val controller = controller(this)

        // The final state cannot tell these apart — the replacement run
        // re-publishes Building through its own progress callback either way —
        // so this watches the emission sequence instead.
        val seen = mutableListOf<UpdateState>()
        val watcher = controller.state.onEach { seen += it }.launchIn(this)
        advanceUntilIdle()

        controller.start(first, binding)
        advanceUntilIdle()

        // No advance between these two: the cancelled run's coroutine has not
        // resumed yet, so its cancellation handler is still pending when the
        // replacement publishes Building. That ordering is the race.
        controller.cancel()
        controller.start(second, binding)
        first.gates["updateApkStatus"]?.complete(Unit)
        advanceUntilIdle()

        // Exactly one Idle — the user's own cancel. A second would be the
        // superseded run reporting over the live one, which stops the
        // foreground service out from under an active download.
        assertEquals("only the user's cancel may publish Idle", 1, seen.count { it is UpdateState.Idle })

        watcher.cancel()
        second.gates["updateApkStatus"]?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a successful install drops the staged bytes`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val controller = controller(this, staging = staging)

        controller.onInstallOutcome(ApkInstallOutcome.Success)

        assertEquals(UpdateState.Idle, controller.state.value)
        assertEquals(0L, staging.partialBytes())
        assertNull(staging.identity())
    }

    @Test
    fun `a staged APK older than the installed app is not adopted on rehydrate`() = runTest {
        // What a self-update staged while a different, newer version was
        // being installed looks like: verified bytes the system would only
        // ever reject as a downgrade.
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.3.0", versionCode = 11),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val controller = controller(this, staging = staging, installedVersionCode = 12)

        controller.rehydrate()

        assertEquals(UpdateState.Idle, controller.state.value)
    }

    @Test
    fun `a staged APK whose version is unknown is not adopted on rehydrate`() = runTest {
        // A sidecar written before versionCode was recorded: the version is
        // unknowable, and offering it anyway is how the flow wedges on Ready.
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0"),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val controller = controller(this, staging = staging, installedVersionCode = 12)

        controller.rehydrate()

        assertEquals(UpdateState.Idle, controller.state.value)
    }

    @Test
    fun `discarding a ready update drops the bytes and frees a fresh build`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        staging.record(
            StagedIdentity(commit = "abc1234", sha256 = sha256(bytes), size = bytes.size.toLong(), version = "0.4.0", versionCode = 12),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val notifier = RecordingNotifier()
        val controller = controller(this, notifier = notifier, staging = staging)
        controller.rehydrate()
        assertTrue(controller.state.value is UpdateState.Ready)

        controller.discardStaged()

        assertEquals(UpdateState.Idle, controller.state.value)
        assertEquals(0L, staging.partialBytes())
        assertNull(staging.identity())
        assertEquals(1, notifier.cancelled)

        // The whole point of the escape hatch: Idle again means build and
        // download is reachable once more.
        controller.start(readyApi(), binding)
        advanceUntilIdle()
        assertTrue(controller.state.value is UpdateState.Ready)
    }

    @Test
    fun `a host build older than the installed app fails instead of wedging on Ready`() = runTest {
        val staging = UpdateStaging(File(files.root, "update"))
        val controller = controller(this, staging = staging, installedVersionCode = 20)

        // readyApi()'s artifact carries versionCode 12 — a downgrade against
        // the running app, so committing it is impossible no matter what.
        controller.start(readyApi(), binding)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is UpdateState.Failed)
        assertFalse((state as UpdateState.Failed).resumable)
        assertEquals(0L, staging.partialBytes())
        assertNull(staging.identity())
    }
}

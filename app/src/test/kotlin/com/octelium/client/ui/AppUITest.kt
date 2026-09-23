package com.octelium.client.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.github.takahirom.roborazzi.captureRoboImage
import com.octelium.client.AppContainer
import com.octelium.client.prefs.PrefsRepository
import com.octelium.client.runtime.RuntimeState
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.daemon.v1.Daemonv1.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w400dp-h860dp-xxhdpi", sdk = [36])
class AppUITest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverName = "cluster-${UUID.randomUUID()}"
    private val server = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(FakeUserService())
        .build()
        .start()

    @After
    fun tearDown() {
        server.shutdownNow()
        scope.cancel()
    }

    private fun launch(
        domains: List<Daemonv1.DomainState>,
        runtimeState: RuntimeState? = null,
    ): FakeDaemon {
        lateinit var daemon: FakeDaemon

        val container = AppContainer(
            context = RuntimeEnvironment.getApplication(),
            getRuntime = { c ->
                daemon = FakeDaemon(c.statusStore, domains)
                FakeRuntime(runtimeState ?: getReadyState(daemon))
            },
            channels = { InProcessChannelBuilder.forName(serverName).directExecutor().build() },
            preferences = PrefsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "prefs.preferences_pb") },
            ),
        )

        container.statusStore.update(daemon.getStatus())

        val vm = MainViewModel(container)
        composeRule.setContent {
            OcteliumUI(vm)
        }

        return daemon
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val dir = System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi"
        composeRule.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Test
    fun testSignIn() {
        launch(emptyList())

        waitForText("Welcome to Octelium")
        composeRule.onNodeWithText("Continue in browser").assertExists()
        composeRule.onNodeWithText("Use an authentication Token").assertExists()
        capture("sign-in")

        composeRule.onNode(hasText("example.com")).performTextInput("invalid")
        composeRule.onNodeWithText("Continue in browser").performClick()

        waitForText("Invalid Cluster domain")
        composeRule.onNodeWithText("Could not start sign in").assertExists()
    }

    @Test
    @Config(qualifiers = "+night")
    fun testSignInDark() {
        launch(emptyList())

        waitForText("Welcome to Octelium")
        capture("sign-in-dark")
    }

    @Test
    fun testConnected() {
        val daemon = launch(listOf(getAuthenticatedDomain("example.com", ConnectionStatus.State.CONNECTED)))

        waitForText("Tunnel")
        composeRule.onNodeWithText("Disconnect").assertExists()
        composeRule.onNodeWithText("Signed in").assertExists()
        composeRule.onNodeWithText("WireGuard").assertExists()
        composeRule.onNodeWithText("100.64.0.5/32").assertExists()
        composeRule.onNodeWithText("Connected for 1h 2m").assertExists()

        waitForText("alice@example.com")
        capture("connection-connected")

        composeRule.onNodeWithText("Disconnect").performClick()

        waitForText("Connect")
        composeRule.waitUntil(10_000) { daemon.calls.contains("Disconnect") }
        waitForText("Your Cluster credentials are ready")
    }

    @Test
    @Config(qualifiers = "+night")
    fun testConnectedDark() {
        launch(listOf(getAuthenticatedDomain("example.com", ConnectionStatus.State.CONNECTED)))

        waitForText("alice@example.com")
        capture("connection-connected-dark")
    }

    @Test
    fun testDisconnected() {
        launch(
            listOf(
                getAuthenticatedDomain("example.com"),
                getLoggedOutDomain("staging.example.com"),
            ),
        )

        waitForText("Your Cluster credentials are ready")
        composeRule.onNodeWithText("Disconnected").assertExists()
        composeRule.onNodeWithText("Services").assertExists()
        capture("connection-disconnected")

        composeRule.onNodeWithContentDescription("Open Cluster menu").performClick()
        waitForText("Manage Clusters")
        capture("domain-switcher")
    }

    @Test
    fun testSignedOut() {
        launch(listOf(getLoggedOutDomain("example.com")))

        waitForText("Sign in to example.com")
        composeRule.onNodeWithText("Continue in browser").assertExists()
        assertTrue(composeRule.onAllNodesWithText("Services").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun testServices() {
        launch(listOf(getAuthenticatedDomain("example.com", ConnectionStatus.State.CONNECTED)))

        waitForText("Services")
        composeRule.onNodeWithText("Services").performClick()

        waitForText("The Services you are authorized to access at example.com")
        waitForText("portal")
        composeRule.onNodeWithText("Company Portal").assertExists()
        composeRule.onNodeWithText("Web App").assertExists()
        composeRule.onNodeWithText("Open").assertExists()
        capture("services")

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("PostgreSQL"))
        composeRule.onNodeWithText("postgres-production").assertExists()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Company Portal"))

        composeRule.onNodeWithText("Company Portal").performClick()
        waitForText("PRIVATE FQDN")
        composeRule.onNodeWithText("portal.local.example.com").assertExists()
        capture("services-details")
    }

    @Test
    @Config(qualifiers = "+night")
    fun testServicesDark() {
        launch(listOf(getAuthenticatedDomain("example.com", ConnectionStatus.State.CONNECTED)))

        waitForText("Services")
        composeRule.onNodeWithText("Services").performClick()
        waitForText("Company Portal")
        capture("services-dark")
    }

    @Test
    fun testSettings() {
        val daemon = launch(
            listOf(
                getAuthenticatedDomain("example.com"),
                getLoggedOutDomain("staging.example.com"),
            ),
        )

        waitForText("Settings")
        composeRule.onNodeWithText("Settings").performClick()

        waitForText("Application settings")
        composeRule.onNodeWithText("Theme").assertExists()
        composeRule.onNodeWithText("Always-on VPN").assertExists()
        capture("settings-application")

        composeRule.onNodeWithText("Cluster").performClick()
        waitForText("Cluster settings")
        composeRule.onNodeWithText("Auto connect").assertExists()
        composeRule.onNodeWithText("Split DNS").assertExists()
        capture("settings-cluster")

        composeRule.onNodeWithText("Clusters").performClick()
        waitForText("Manage Clusters")
        waitForText("staging.example.com")
        composeRule.onNodeWithText("Add another Cluster").assertExists()
        capture("settings-clusters")

        composeRule.onNodeWithText("Cluster").performClick()
        waitForText("Auto connect")
        composeRule.onNode(hasText("Auto connect")).assertExists()

        assertTrue(daemon.calls.none { it == "UpdateDomainSettings" })
    }

    @Test
    fun testDiagnostics() {
        launch(listOf(getAuthenticatedDomain("example.com", ConnectionStatus.State.CONNECTED)))

        waitForText("Settings")
        composeRule.onNodeWithText("Settings").performClick()
        waitForText("View")
        composeRule.onNodeWithText("View").performScrollTo().performClick()

        waitForText("Diagnostics")
        waitForText("Available")
        composeRule.onNodeWithText("v1.0").assertExists()
        composeRule.onNodeWithText(TEST_CALLBACK_URL).assertExists()
        capture("diagnostics")
    }

    @Test
    fun testStartupError() {
        launch(emptyList(), RuntimeState.Failed("Could not decrypt the state", isResettable = true))

        waitForText("Octelium could not start")
        composeRule.onNodeWithText("Could not decrypt the state").assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
        composeRule.onNodeWithText("Reset local state").performClick()

        waitForText("Reset the local state")
        capture("startup-error")
    }

    @Test
    fun testOperationBanner() {
        launch(
            listOf(
                getLoggedOutDomain("example.com").toBuilder()
                    .setLastOperation(
                        Daemonv1.Operation.newBuilder()
                            .setId("op")
                            .setDomain("example.com")
                            .setType(Daemonv1.Operation.Type.AUTHENTICATE)
                            .setState(Daemonv1.Operation.State.WAITING_FOR_USER)
                            .setCancellable(true)
                            .setAction(
                                Daemonv1.Action.newBuilder()
                                    .setOpenURL(Daemonv1.Action.OpenURL.newBuilder().setUrl("https://example.com/login"))
                            )
                    )
                    .build(),
            ),
        )

        waitForText("Signing in example.com")
        composeRule.onNodeWithText("Open the browser again").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
        capture("operation-banner")
    }
}

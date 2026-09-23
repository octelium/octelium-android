package com.octelium.client.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.octelium.client.R
import com.octelium.client.core.domain.getAuthenticationStateLabel
import com.octelium.client.core.domain.getConnectionStateLabel
import com.octelium.client.core.domain.getConnectionStateTone
import com.octelium.client.core.domain.getDomainState
import com.octelium.client.core.domain.isAuthenticated
import com.octelium.client.core.domain.isConnectionBusy
import com.octelium.client.core.prefs.ThemeMode
import com.octelium.client.core.prefs.getNextThemeMode
import com.octelium.client.core.prefs.getThemeModeLabel
import com.octelium.client.core.prefs.resolveTheme
import com.octelium.client.runtime.RuntimeState
import com.octelium.client.ui.components.AlertBox
import com.octelium.client.ui.components.AlertText
import com.octelium.client.ui.components.ButtonVariant
import com.octelium.client.ui.components.ConfirmDialog
import com.octelium.client.ui.components.OctButton
import com.octelium.client.ui.components.OctIconButton
import com.octelium.client.ui.components.StatusDot
import com.octelium.client.ui.connection.ConnectionScreen
import com.octelium.client.ui.diagnostics.DiagnosticsScreen
import com.octelium.client.ui.services.ServicesScreen
import com.octelium.client.ui.settings.SettingsScreen
import com.octelium.client.ui.theme.AlertTone
import com.octelium.client.ui.theme.OcteliumTheme
import android.graphics.Color as AndroidColor

object Routes {
    const val CONNECTION = "connection"
    const val SERVICES = "services"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun OcteliumUI(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val isDark = resolveTheme(prefs?.theme ?: ThemeMode.SYSTEM, isSystemInDarkTheme())
    val context = LocalContext.current

    DisposableEffect(isDark) {
        val style = if (isDark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }

    OcteliumTheme(isDark = isDark) {
        Surface(modifier = Modifier.fillMaxSize(), color = OcteliumTheme.colors.app) {
            val runtime by vm.runtimeState.collectAsStateWithLifecycle()

            when (val state = runtime) {
                RuntimeState.Loading -> SplashScreen()
                is RuntimeState.Failed -> StartupErrorScreen(vm, state)
                is RuntimeState.Ready -> MainScaffold(vm)
            }
        }
    }
}

@Composable
private fun LogoCircle(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = "Octelium",
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size((size * 0.62f).dp),
        )
    }
}

@Composable
private fun SplashScreen() {
    val colors = OcteliumTheme.colors

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoCircle(size = 96)
        Spacer(modifier = Modifier.height(28.dp))
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.muted, strokeWidth = 2.dp)
    }
}

@Composable
private fun StartupErrorScreen(vm: MainViewModel, state: RuntimeState.Failed) {
    val colors = OcteliumTheme.colors
    var isConfirmingReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoCircle(size = 96)

        Text(
            text = "Octelium could not start",
            modifier = Modifier.padding(top = 28.dp),
            color = colors.strong,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )

        AlertBox(
            tone = AlertTone.RED,
            modifier = Modifier
                .padding(top = 20.dp)
                .widthIn(max = 480.dp),
            icon = R.drawable.ic_triangle_alert,
        ) {
            AlertText(text = state.message)
        }

        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OctButton(text = "Try again", onClick = vm::retryStart, icon = R.drawable.ic_refresh_cw)

            if (state.isResettable) {
                OctButton(
                    text = "Reset local state",
                    onClick = { isConfirmingReset = true },
                    variant = ButtonVariant.OUTLINE,
                    isDanger = true,
                )
            }
        }
    }

    ConfirmDialog(
        isOpen = isConfirmingReset,
        title = "Reset the local state",
        text = "Resetting removes every stored Cluster domain, its credentials and its settings from this device. You will have to sign in again.",
        confirmLabel = "Reset",
        isDanger = true,
        onConfirm = {
            isConfirmingReset = false
            vm.resetState()
        },
        onDismiss = { isConfirmingReset = false },
    )
}

private data class NavItem(
    val route: String,
    val title: String,
    @DrawableRes val icon: Int,
)

@Composable
private fun MainScaffold(vm: MainViewModel) {
    val colors = OcteliumTheme.colors
    val navController = rememberNavController()
    val status by vm.status.collectAsStateWithLifecycle()
    val domain by vm.selectedDomain.collectAsStateWithLifecycle()
    val isAuthenticated = isAuthenticated(getDomainState(status, domain))

    val items = listOfNotNull(
        NavItem(
            Routes.CONNECTION,
            if (isAuthenticated) "Connection" else "Sign in",
            if (isAuthenticated) R.drawable.ic_activity else R.drawable.ic_log_in,
        ),
        if (isAuthenticated) NavItem(Routes.SERVICES, "Services", R.drawable.ic_panel_top) else null,
        NavItem(Routes.SETTINGS, "Settings", R.drawable.ic_settings),
    )

    val navigate: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = colors.app,
        topBar = { TopBar(vm, onManageClusters = { navigate("${Routes.SETTINGS}?section=clusters") }) },
        bottomBar = { BottomBar(navController, items, navigate) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CONNECTION,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            composable(Routes.CONNECTION) {
                ConnectionScreen(vm)
            }

            composable(Routes.SERVICES) {
                ServicesScreen(vm, onNavigateToConnection = { navigate(Routes.CONNECTION) })
            }

            composable(
                route = "${Routes.SETTINGS}?section={section}",
                arguments = listOf(
                    navArgument("section") {
                        type = NavType.StringType
                        nullable = true
                    },
                ),
            ) { entry ->
                SettingsScreen(
                    vm = vm,
                    initialSection = entry.arguments?.getString("section"),
                    onNavigateToConnection = { navigate(Routes.CONNECTION) },
                    onNavigateToDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }

            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(vm)
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, items: List<NavItem>, navigate: (String) -> Unit) {
    val colors = OcteliumTheme.colors
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route?.substringBefore("?")

    Column {
        HorizontalDivider(color = colors.line)

        NavigationBar(containerColor = colors.app, tonalElevation = 0.dp) {
            for (itm in items) {
                NavigationBarItem(
                    selected = current == itm.route || (current == Routes.DIAGNOSTICS && itm.route == Routes.SETTINGS),
                    onClick = { navigate(itm.route) },
                    icon = {
                        Icon(painter = painterResource(itm.icon), contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    label = {
                        Text(text = itm.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.inverseFg,
                        selectedTextColor = colors.strong,
                        indicatorColor = colors.inverse,
                        unselectedIconColor = colors.body,
                        unselectedTextColor = colors.muted,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(vm: MainViewModel, onManageClusters: () -> Unit) {
    val colors = OcteliumTheme.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val selected by vm.selectedDomain.collectAsStateWithLifecycle()
    val current = getDomainState(status, selected)
    val theme = prefs?.theme ?: ThemeMode.SYSTEM

    var isSheetOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.background(colors.app).statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_wordmark),
                contentDescription = "Octelium",
                colorFilter = ColorFilter.tint(colors.strong),
                modifier = Modifier.width(112.dp),
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .widthIn(max = 170.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .clickable { isSheetOpen = true }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(
                    tone = getConnectionStateTone(current?.connection?.state),
                    size = 8.dp,
                    pulse = isConnectionBusy(current),
                )
                Text(
                    text = selected ?: "Set up",
                    modifier = Modifier.weight(1f, fill = false),
                    color = colors.strong,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevrons_up_down),
                    contentDescription = "Open Cluster menu",
                    tint = colors.faint,
                    modifier = Modifier.size(14.dp),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OctIconButton(
                icon = when (theme) {
                    ThemeMode.LIGHT -> R.drawable.ic_sun
                    ThemeMode.DARK -> R.drawable.ic_moon
                    ThemeMode.SYSTEM -> R.drawable.ic_monitor
                },
                contentDescription = "${getThemeModeLabel(theme)} theme",
                onClick = { vm.setTheme(getNextThemeMode(theme)) },
                variant = ButtonVariant.DEFAULT,
                iconSize = 17.dp,
            )
        }

        HorizontalDivider(color = colors.line)
    }

    if (isSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
        ) {
            DomainSheet(
                vm = vm,
                onSelect = { isSheetOpen = false },
                onManageClusters = {
                    isSheetOpen = false
                    onManageClusters()
                },
            )
        }
    }
}

@Composable
private fun DomainSheet(vm: MainViewModel, onSelect: () -> Unit, onManageClusters: () -> Unit) {
    val colors = OcteliumTheme.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val selected by vm.selectedDomain.collectAsStateWithLifecycle()

    val domains = status?.domainsList.orEmpty()
    val current = getDomainState(status, selected)
    val visible = when {
        prefs?.multiCluster == true -> domains
        current != null -> listOf(current)
        else -> domains.take(1)
    }

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text(
            text = "CLUSTER DOMAINS",
            modifier = Modifier.padding(bottom = 8.dp),
            color = colors.faint,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )

        for (itm in visible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (itm.domain == selected) colors.surface3 else Color.Transparent)
                    .clickable {
                        vm.selectDomain(itm.domain)
                        onSelect()
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(tone = getConnectionStateTone(itm.connection.state), pulse = isConnectionBusy(itm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = itm.domain,
                        color = colors.strong,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isAuthenticated(itm)) {
                            getConnectionStateLabel(itm.connection.state)
                        } else {
                            getAuthenticationStateLabel(itm.authentication.state)
                        },
                        color = colors.muted,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (domains.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = colors.line)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onManageClusters)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painter = painterResource(R.drawable.ic_plus), contentDescription = null, tint = colors.strong, modifier = Modifier.size(16.dp))
            Text(text = "Manage Clusters", color = colors.strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

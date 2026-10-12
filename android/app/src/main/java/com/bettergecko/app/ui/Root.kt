package com.bettergecko.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.BuildConfig
import com.bettergecko.app.R
import kotlinx.coroutines.launch

@Composable
fun Root(vm: AppViewModel) {
    val loading by vm.isLoadingSession.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    when {
        loading -> SplashScreen()
        session != null -> LoggedIn(vm)
        else -> LoginScreen(vm)
    }
}

/** Routes outside the tab bar. Device-scoped screens take the GSN as a path argument. */
object Routes {
    const val TABS = "tabs"
    fun device(gsn: String) = "device/$gsn"
    fun settings(gsn: String) = "settings/$gsn"
    fun schedules(gsn: String) = "schedules/$gsn"
}

@Composable
private fun LoggedIn(vm: AppViewModel) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.TABS) {
        composable(Routes.TABS) { MainTabs(vm, nav) }
        composable("device/{gsn}") { e ->
            DeviceDetailScreen(vm, nav, e.arguments?.getString("gsn") ?: "", showBack = true)
        }
        composable("settings/{gsn}") { e ->
            DeviceSettingsRoute(vm, nav, e.arguments?.getString("gsn") ?: "")
        }
        composable("schedules/{gsn}") { e ->
            SchedulesScreen(vm, nav, e.arguments?.getString("gsn") ?: "")
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("Summary", Icons.Filled.Info),
    Tab("Stats", Icons.Filled.Thermostat),
    Tab("Graphs", Icons.AutoMirrored.Filled.ShowChart),
    Tab("Settings", Icons.Filled.Settings),
    Tab("Profile", Icons.Filled.AccountCircle),
)

@Composable
private fun MainTabs(vm: AppViewModel, nav: NavHostController) {
    // rememberSaveable survives rotation and returning from a pushed screen.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            when (selected) {
                0 -> SummaryScreen(vm)
                1 -> StatsScreen(vm, nav)
                2 -> GraphsScreen(vm)
                3 -> SettingsTab(vm, nav)
                4 -> ProfileScreen(vm)
            }
        }
    }
}

@Composable
fun SplashScreen() {
    val iconScale = remember { Animatable(0.6f) }
    val iconAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val subAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        kotlinx.coroutines.coroutineScope {
            launch { iconScale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) }
            launch { iconAlpha.animateTo(1f, tween(400)) }
            launch { kotlinx.coroutines.delay(250); textAlpha.animateTo(1f, tween(500)) }
            launch { kotlinx.coroutines.delay(450); subAlpha.animateTo(1f, tween(400)) }
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF148C47), Color(0xFF0A5229)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painterResource(R.drawable.gecko_icon), null,
                Modifier.size(128.dp).scale(iconScale.value).alpha(iconAlpha.value).clip(RoundedCornerShape(28.dp)),
            )
            Spacer(Modifier.height(40.dp))
            Text("BetterGecko", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(textAlpha.value))
            Spacer(Modifier.height(8.dp))
            Text("Solar Geyser Controller", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.alpha(subAlpha.value))
        }
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                .alpha(subAlpha.value),
        )
    }
}

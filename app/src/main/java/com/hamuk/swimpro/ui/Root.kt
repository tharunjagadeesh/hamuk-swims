package com.hamuk.swimpro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hamuk.swimpro.container
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val tabs = listOf(
    TabItem("home", "Home", Icons.Rounded.Home),
    TabItem("swims", "Swims", Icons.Rounded.Pool),
    TabItem("coach", "Coach", Icons.Rounded.SportsScore),
    TabItem("progress", "Progress", Icons.Rounded.Insights),
)
private val tabRoutes = tabs.map { it.route }.toSet()

/** Standard scrolling page: side gutters, spacing, and room for the floating tab bar. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        content()
        Spacer(Modifier.navigationBarsPadding().height(108.dp))
    }
}

@Composable
fun SwimProRoot(showPrivacy: Boolean) {
    val app = LocalContext.current.container
    val onboarded by app.settings.onboarded.collectAsState(initial = null)
    val haze = rememberHazeState()

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize()) {
            WaterBackground(Modifier.hazeSource(haze))
            when (onboarded) {
                null -> Unit
                false -> {
                    if (showPrivacy) PrivacyScreen(onBack = null) else OnboardingScreen()
                    StatusBarScrim()
                }
                true -> MainShell(showPrivacy)
            }
        }
    }
}

/** Soft fade behind the status bar so scrolled content doesn't collide with the clock. */
@Composable
fun StatusBarScrim() {
    val p = LocalPalette.current
    Box(
        Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Brush.verticalGradient(listOf(p.bgTop.copy(alpha = 0.92f), p.bgTop.copy(alpha = 0.6f)))),
    )
}

@Composable
private fun MainShell(showPrivacy: Boolean) {
    val app = LocalContext.current.container
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(Unit) {
        if (showPrivacy) nav.navigate("privacy") else {
            runCatching { app.sync() }
            runCatching { app.updater.check() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AppNavHost(nav)
        StatusBarScrim()
        AnimatedVisibility(
            visible = route in tabRoutes,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it * 2 } + fadeOut(tween(160)),
        ) {
            GlassTabBar(
                tabs,
                selected = tabs.indexOfFirst { it.route == route },
                onSelect = { i ->
                    nav.navigate(tabs[i].route) {
                        popUpTo("home") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

@Composable
private fun AppNavHost(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        nav, startDestination = "home",
        enterTransition = {
            if (isTabSwitch()) fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.98f)
            else slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it } + fadeIn(tween(200))
        },
        exitTransition = {
            if (isTabSwitch()) fadeOut(tween(180))
            else slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(260))
        },
        popEnterTransition = {
            slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { -it / 4 } + fadeIn(tween(240))
        },
        popExitTransition = {
            slideOutHorizontally(tween(300)) { it } + fadeOut(tween(260))
        },
    ) {
        composable("home") {
            HomeScreen(
                onOpenSwim = { nav.navigate("swim/$it") },
                onOpenWorkout = { nav.navigate("workout/$it") },
                onSettings = { nav.navigate("settings") },
                onChat = { nav.navigate("chat") },
                onSetGoal = { nav.navigate("goal") },
                onLogTest = { nav.navigate("test") },
                onAddSwim = { nav.navigate("add_swim") },
                onCompare = { a, b -> nav.navigate("compare/swims/$a/$b") },
                onGoalNav = { nav.navigate("goal_nav") },
            )
        }
        composable("swims") { SwimsScreen(onOpen = { nav.navigate("swim/$it") }, onAdd = { nav.navigate("add_swim") }) }
        composable("swim/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            SwimDetailScreen(it.arguments!!.getLong("id"), onBack = back, onReview = { nav.navigate("chat") },
                onCompare = { a, b -> nav.navigate("compare/swims/$a/$b") }, onCompareDays = { a, b -> nav.navigate("compare/days/$a/$b") })
        }
        composable("add_swim") { AddSwimScreen(onBack = back) }
        composable(
            "compare/{mode}/{a}/{b}",
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("a") { type = NavType.LongType },
                navArgument("b") { type = NavType.LongType },
            ),
        ) {
            val args = it.arguments!!
            val mode = when (args.getString("mode")) { "days" -> CompareMode.DAYS; "weeks" -> CompareMode.WEEKS; else -> CompareMode.SWIMS }
            CompareScreen(mode, args.getLong("a"), args.getLong("b"), onBack = back, onOpenSwim = { id -> nav.navigate("swim/$id") })
        }
        composable("progress") { ProgressScreen(onOpenSwim = { nav.navigate("swim/$it") }, onCompareWeeks = { a, b -> nav.navigate("compare/weeks/$a/$b") }) }
        composable("coach") {
            CoachScreen(
                onSetGoal = { nav.navigate("goal") },
                onLogTest = { nav.navigate("test") },
                onOpenWorkout = { nav.navigate("workout/$it") },
                onChat = { nav.navigate("chat") },
                onProfile = { nav.navigate("profile") },
                onGoalNav = { nav.navigate("goal_nav") },
            )
        }
        composable("goal_nav") {
            GoalNavScreen(onBack = back, onOpenWorkout = { nav.navigate("workout/$it") }, onSetGoal = { nav.navigate("goal") })
        }
        composable("goal") { GoalScreen(onBack = back) }
        composable("test") { TestScreen(onBack = back) }
        composable("workout/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            WorkoutScreen(it.arguments!!.getLong("id"), onBack = back, onOpenSwim = { id -> nav.navigate("swim/$id") })
        }
        composable("chat") { ChatScreen(onBack = back, onOpenSettings = { nav.navigate("settings") }) }
        composable("settings") {
            SettingsScreen(
                onBack = back,
                onInspector = { nav.navigate("inspector") },
                onPrivacy = { nav.navigate("privacy") },
                onProfile = { nav.navigate("profile") },
            )
        }
        composable("profile") { ProfileScreen(onBack = back) }
        composable("inspector") { InspectorScreen(onBack = back) }
        composable("privacy") { PrivacyScreen(onBack = back) }
    }
}


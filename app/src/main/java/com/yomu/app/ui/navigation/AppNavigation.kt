package com.yomu.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.yomu.app.ui.theme.rememberReduceMotion
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yomu.app.ui.theme.ChromeContent
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yomu.app.ui.home.HomeViewModel
import com.yomu.app.ui.home.SetupScreen
import com.yomu.app.ui.theme.PaperLoading
import com.yomu.app.ui.home.HomeScreen
import androidx.navigation.compose.navigation
import com.yomu.app.ui.settings.AppearanceSettings
import com.yomu.app.ui.settings.PerformanceSettings
import com.yomu.app.ui.settings.PipelineSettings
import com.yomu.app.ui.settings.SettingsScreen
import com.yomu.app.ui.settings.LanguageOfferDialog
import com.yomu.app.ui.settings.NameGlossarySettings
import com.yomu.app.ui.settings.SettingsSection
import com.yomu.app.ui.settings.SettingsViewModel
import com.yomu.app.ui.settings.TranslationSettings
import com.yomu.app.ui.settings.TypesettingSettings
import com.yomu.core.TargetLanguage

private const val SETTINGS_ROOT = "settings/root"

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Home, Screen.Settings)

@Composable
fun AppNavigation(
    onRequestScreenCapture: () -> Unit = {},
    languageRequest: TargetLanguage? = null,
    onLanguageRequestHandled: () -> Unit = {}
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    // The overlay's language switch opens the app when the choice needs the model offer (#338).
    LaunchedEffect(languageRequest) {
        languageRequest?.let(viewModel::chooseLanguage)
        onLanguageRequestHandled()
    }
    state.languageOffer?.let {
        LanguageOfferDialog(it, state.targetLanguage, viewModel::acceptLanguageOffer, viewModel::declineLanguageOffer)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshEnvironment()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (state.modelsLoading || state.setupVisible) {
        ChromeContent(Modifier.safeDrawingPadding()) {
            if (state.modelsLoading) PaperLoading("Getting Yomu ready…")
            else SetupScreen(state, viewModel)
        }
        return
    }
    val navController = rememberNavController()
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 2.dp, color = scheme.surface) {
                NavigationBar(
                    modifier = Modifier.heightIn(min = 80.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                    containerColor = scheme.surface, tonalElevation = 0.dp
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(screen.title, style = MaterialTheme.typography.labelMedium) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = scheme.onPrimary,
                                selectedTextColor = scheme.onSurface,
                                indicatorColor = scheme.primary,
                                unselectedIconColor = scheme.onSurfaceVariant,
                                unselectedTextColor = scheme.onSurfaceVariant
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Home.route)
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        ChromeContent(Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                enterTransition = {
                    if (reduceMotion) fadeIn(tween(180))
                    else fadeIn(tween(180)) + slideInHorizontally(tween(200)) { it / 12 }
                },
                exitTransition = {
                    if (reduceMotion) fadeOut(tween(180))
                    else fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { -it / 12 }
                }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(viewModel, onRequestScreenCapture)
                }
                navigation(startDestination = SETTINGS_ROOT, route = Screen.Settings.route) {
                    composable(SETTINGS_ROOT) {
                        SettingsScreen(onOpen = { navController.navigate(it.route) { launchSingleTop = true } })
                    }
                    SettingsSection.entries.forEach { section ->
                        composable(section.route) { entry ->
                            // One ViewModel for the whole settings graph, so a download survives leaving its sub-screen.
                            val parent = remember(entry) { navController.getBackStackEntry(Screen.Settings.route) }
                            val settings: SettingsViewModel = hiltViewModel(parent)
                            val onBack: () -> Unit = { navController.popBackStack() }
                            when (section) {
                                SettingsSection.Translation -> TranslationSettings(settings, onBack)
                                SettingsSection.NameGlossary -> NameGlossarySettings(hiltViewModel(), onBack)
                                SettingsSection.Pipeline -> PipelineSettings(settings, onBack)
                                SettingsSection.Typesetting -> TypesettingSettings(settings, onBack)
                                SettingsSection.Performance -> PerformanceSettings(settings, onBack)
                                SettingsSection.Appearance -> AppearanceSettings(settings, hiltViewModel(), onBack)
                            }
                        }
                    }
                }
            }
        }
    }
}

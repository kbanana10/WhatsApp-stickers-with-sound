package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.ui.screens.PackDetailScreen
import com.example.ui.screens.PacksScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.AudioStickersTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.PacksViewModel
import com.example.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.launch

object Routes {
    const val STUDIO = "studio"
    const val PACKS = "packs"
    const val PACK_DETAIL = "pack_detail/{packId}"
    fun packDetail(packId: String) = "pack_detail/$packId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefsRepo = remember { UserPreferencesRepository(applicationContext) }
            val prefs by prefsRepo.preferences.collectAsStateWithLifecycle(initialValue = UserPreferences())
            val scope = rememberCoroutineScope()
            val isArabic = prefs.language == AppLanguage.AR
            val strings = AppStrings.get(prefs.language)

            AudioStickersTheme(themeMode = prefs.themeMode, isArabic = isArabic) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    AudioStickersApp(
                        prefs = prefs,
                        strings = strings,
                        onToggleLanguage = {
                            scope.launch {
                                val next = if (prefs.language == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR
                                prefsRepo.setLanguage(next)
                            }
                        },
                        onCycleTheme = {
                            scope.launch {
                                val next = when (prefs.themeMode) {
                                    ThemeMode.AUTO -> ThemeMode.DARK
                                    ThemeMode.DARK -> ThemeMode.LIGHT
                                    ThemeMode.LIGHT -> ThemeMode.AUTO
                                }
                                prefsRepo.setThemeMode(next)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioStickersApp(
    prefs: UserPreferences,
    strings: com.example.i18n.Strings,
    onToggleLanguage: () -> Unit,
    onCycleTheme: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.STUDIO

    val studioViewModel: StudioViewModel = viewModel()
    val packsViewModel: PacksViewModel = viewModel()

    val showChrome = currentRoute == Routes.STUDIO || currentRoute == Routes.PACKS

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.navigationBars,
            topBar = {
                if (showChrome) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_brand_logo),
                                    contentDescription = strings.appTitle,
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                                )
                                Column {
                                    Text(
                                        text = strings.appTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = strings.appSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        actions = {
                            Surface(
                                onClick = onToggleLanguage,
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.testTag("lang_toggle_button")
                            ) {
                                Text(
                                    text = if (prefs.language == AppLanguage.AR) "EN" else "العربية",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = onCycleTheme,
                                modifier = Modifier.testTag("theme_toggle_button")
                            ) {
                                Icon(
                                    imageVector = when (prefs.themeMode) {
                                        ThemeMode.AUTO -> Icons.Default.BrightnessAuto
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                    },
                                    contentDescription = when (prefs.themeMode) {
                                        ThemeMode.AUTO -> strings.themeAuto
                                        ThemeMode.LIGHT -> strings.themeLight
                                        ThemeMode.DARK -> strings.themeDark
                                    }
                                )
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (showChrome && !useNavRail) {
                    NavigationBar(windowInsets = WindowInsets.navigationBars) {
                        NavigationBarItem(
                            selected = currentRoute == Routes.STUDIO,
                            onClick = {
                                navController.navigate(Routes.STUDIO) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.STUDIO) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                                    contentDescription = strings.navStudio
                                )
                            },
                            label = { Text(strings.navStudio) },
                            modifier = Modifier.testTag("nav_studio_tab")
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.PACKS,
                            onClick = {
                                navController.navigate(Routes.PACKS) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.PACKS) Icons.Filled.FolderSpecial else Icons.Outlined.FolderSpecial,
                                    contentDescription = strings.navPacks
                                )
                            },
                            label = { Text(strings.navPacks) },
                            modifier = Modifier.testTag("nav_packs_tab")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showChrome && useNavRail) {
                    NavigationRail {
                        NavigationRailItem(
                            selected = currentRoute == Routes.STUDIO,
                            onClick = {
                                navController.navigate(Routes.STUDIO) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.STUDIO) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                                    contentDescription = strings.navStudio
                                )
                            },
                            label = { Text(strings.navStudio) }
                        )
                        NavigationRailItem(
                            selected = currentRoute == Routes.PACKS,
                            onClick = {
                                navController.navigate(Routes.PACKS) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (currentRoute == Routes.PACKS) Icons.Filled.FolderSpecial else Icons.Outlined.FolderSpecial,
                                    contentDescription = strings.navPacks
                                )
                            },
                            label = { Text(strings.navPacks) }
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Routes.STUDIO,
                    modifier = Modifier.weight(1f).fillMaxSize()
                ) {
                    composable(Routes.STUDIO) {
                        StudioScreen(viewModel = studioViewModel, strings = strings)
                    }
                    composable(Routes.PACKS) {
                        PacksScreen(
                            viewModel = packsViewModel,
                            strings = strings,
                            onOpenPack = { packId ->
                                navController.navigate(Routes.packDetail(packId))
                            }
                        )
                    }
                    composable(
                        route = Routes.PACK_DETAIL,
                        arguments = listOf(navArgument("packId") { type = NavType.StringType })
                    ) { entry ->
                        val packId = entry.arguments?.getString("packId").orEmpty()
                        PackDetailScreen(
                            packId = packId,
                            viewModel = packsViewModel,
                            strings = strings,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

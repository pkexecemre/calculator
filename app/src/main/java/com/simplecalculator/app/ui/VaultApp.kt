package com.simplecalculator.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.simplecalculator.app.R
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.ui.screens.HomeScreen
import com.simplecalculator.app.ui.screens.LibraryScreen
import com.simplecalculator.app.ui.screens.PlayerScreen
import com.simplecalculator.app.ui.screens.SearchScreen
import com.simplecalculator.app.ui.screens.SettingsScreen
import com.simplecalculator.app.ui.screens.StarScreen
import com.simplecalculator.app.ui.screens.StarsScreen
import com.simplecalculator.app.ui.theme.TextMuted

@Composable
fun VaultApp() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("calc_state", android.content.Context.MODE_PRIVATE) }
    var category by remember {
        mutableStateOf(
            runCatching { Category.valueOf(prefs.getString("home_category", Category.LIVE.name).orEmpty()) }
                .getOrDefault(Category.LIVE)
        )
    }
    fun applyCategory(next: Category) {
        category = next
        prefs.edit().putString("home_category", next.name).apply()
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route == null || (
        !route.startsWith("player") &&
            !route.startsWith("star/")
        )

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    BarItem(nav, "home", Icons.Filled.Home, R.string.nav_home, route)
                    BarItem(nav, "search", Icons.Filled.Search, R.string.nav_search, route)
                    BarItem(nav, "stars", Icons.Filled.Star, R.string.nav_stars, route)
                    BarItem(nav, "library", Icons.Filled.Bookmarks, R.string.nav_library, route)
                    BarItem(nav, "settings", Icons.Filled.Settings, R.string.nav_settings, route)
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                // Each source owns its own scroll position, cache and filter.
                key(category) {
                    HomeScreen(
                        category = category,
                        onCategoryChange = { applyCategory(it) },
                        onOpen = { videoKey -> nav.navigate("player/" + Uri.encode(videoKey)) }
                    )
                }
            }
            composable("search") {
                SearchScreen(
                    category = category,
                    onOpen = { key -> nav.navigate("player/" + Uri.encode(key)) }
                )
            }
            composable("stars") {
                StarsScreen(
                    onOpenStar = { slug -> nav.navigate("star/" + Uri.encode(slug)) },
                    onOpenVideo = { key -> nav.navigate("player/" + Uri.encode(key)) }
                )
            }
            composable("library") {
                LibraryScreen(onOpen = { key -> nav.navigate("player/" + Uri.encode(key)) })
            }
            composable("settings") { SettingsScreen() }
            composable(
                route = "star/{slug}",
                arguments = listOf(navArgument("slug") { type = NavType.StringType })
            ) { entry ->
                val slug = entry.arguments?.getString("slug").orEmpty()
                StarScreen(
                    slug = Uri.decode(slug),
                    onBack = { nav.popBackStack() },
                    onOpenVideo = { key -> nav.navigate("player/" + Uri.encode(key)) }
                )
            }
            composable(
                route = "player/{key}",
                arguments = listOf(navArgument("key") { type = NavType.StringType })
            ) { entry ->
                val key = Uri.decode(entry.arguments?.getString("key").orEmpty())
                PlayerScreen(
                    videoKey = key,
                    onBack = { nav.popBackStack() },
                    onOpenVideo = { next -> nav.navigate("player/" + Uri.encode(next)) },
                    onOpenStar = { slug -> nav.navigate("star/" + Uri.encode(slug)) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(
    nav: androidx.navigation.NavHostController,
    routeName: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    currentRoute: String?
) {
    NavigationBarItem(
        selected = currentRoute == routeName,
        onClick = {
            if (currentRoute != routeName) {
                nav.navigate(routeName) {
                    popUpTo("home") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(labelRes)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            unselectedIconColor = TextMuted,
            unselectedTextColor = TextMuted
        )
    )
}

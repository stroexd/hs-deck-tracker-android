package com.stroexd.hsdecktracker.ui

import android.net.Uri
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.ui.cards.CardsScreen
import com.stroexd.hsdecktracker.ui.collection.CollectionScreen
import com.stroexd.hsdecktracker.ui.collection.CraftCheckScreen
import com.stroexd.hsdecktracker.ui.collection.SetDetailScreen
import com.stroexd.hsdecktracker.ui.decks.DeckBuilderScreen
import com.stroexd.hsdecktracker.ui.decks.DeckDetailScreen
import com.stroexd.hsdecktracker.ui.decks.DecksScreen
import com.stroexd.hsdecktracker.ui.meta.MetaDeckDetailScreen
import com.stroexd.hsdecktracker.ui.meta.MetaScreen
import com.stroexd.hsdecktracker.ui.settings.SettingsScreen
import com.stroexd.hsdecktracker.ui.stats.StatsScreen
import com.stroexd.hsdecktracker.ui.tracker.TrackerScreen

object Routes {
    const val DECKS = "decks"
    const val META = "meta"
    const val CARDS = "cards"
    const val COLLECTION = "collection"
    const val STATS = "stats"
    const val DECK = "deck/{id}"
    const val BUILDER = "builder?id={id}&cls={cls}&fmt={fmt}"
    const val META_DECK = "metadeck/{fmt}/{id}"
    const val SET = "set/{set}"
    const val TRACKER = "tracker"
    const val SETTINGS = "settings"
    const val CRAFT_CHECK = "craftcheck"

    fun deck(id: String) = "deck/${Uri.encode(id)}"
    fun builder(id: String? = null, cls: HsClass? = null, format: GameFormat? = null) =
        "builder?id=${Uri.encode(id.orEmpty())}&cls=${cls?.name.orEmpty()}&fmt=${format?.name.orEmpty()}"
    fun metaDeck(format: GameFormat, id: String) = "metadeck/${format.name}/${Uri.encode(id)}"
    fun set(set: String) = "set/${Uri.encode(set)}"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.DECKS, "Decks", Icons.Filled.Style),
    TopLevel(Routes.META, "Meta", Icons.Filled.Leaderboard),
    TopLevel(Routes.CARDS, "Karten", Icons.Filled.GridView),
    TopLevel(Routes.COLLECTION, "Sammlung", Icons.Filled.Inventory2),
    TopLevel(Routes.STATS, "Statistik", Icons.Filled.QueryStats),
)

@Composable
fun HsTrackerApp(
    sharedText: String?,
    onSharedTextHandled: () -> Unit,
    openTracker: Boolean,
    onTrackerOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topLevel.any { it.route == currentRoute }

    LaunchedEffect(sharedText) {
        if (sharedText != null && currentRoute != Routes.DECKS) navController.navigateTopLevel(Routes.DECKS)
    }
    LaunchedEffect(openTracker) {
        if (openTracker) {
            navController.navigate(Routes.TRACKER) { launchSingleTop = true }
            onTrackerOpened()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DECKS,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.DECKS) {
                DecksScreen(
                    navController = navController,
                    sharedText = sharedText,
                    onSharedTextHandled = onSharedTextHandled,
                )
            }
            composable(Routes.META) { MetaScreen(navController) }
            composable(Routes.CARDS) { CardsScreen(navController) }
            composable(Routes.COLLECTION) { CollectionScreen(navController) }
            composable(Routes.STATS) { StatsScreen(navController) }
            composable(Routes.DECK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                DeckDetailScreen(navController, entry.arguments?.getString("id").orEmpty())
            }
            composable(
                Routes.BUILDER,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("cls") { type = NavType.StringType; defaultValue = "" },
                    navArgument("fmt") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments
                DeckBuilderScreen(
                    navController = navController,
                    deckId = args?.getString("id")?.takeIf { it.isNotBlank() },
                    initialClass = args?.getString("cls")?.takeIf { it.isNotBlank() }?.let { HsClass.fromString(it) },
                    initialFormat = args?.getString("fmt")?.takeIf { it.isNotBlank() }?.let { GameFormat.fromString(it) },
                )
            }
            composable(
                Routes.META_DECK,
                arguments = listOf(
                    navArgument("fmt") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                ),
            ) { entry ->
                MetaDeckDetailScreen(
                    navController = navController,
                    format = GameFormat.fromString(entry.arguments?.getString("fmt")) ?: GameFormat.STANDARD,
                    deckId = entry.arguments?.getString("id").orEmpty(),
                )
            }
            composable(Routes.SET, arguments = listOf(navArgument("set") { type = NavType.StringType })) { entry ->
                SetDetailScreen(navController, entry.arguments?.getString("set").orEmpty())
            }
            composable(Routes.TRACKER) { TrackerScreen(navController) }
            composable(Routes.SETTINGS) { SettingsScreen(navController) }
            composable(Routes.CRAFT_CHECK) { CraftCheckScreen(navController) }
        }
    }
}

fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

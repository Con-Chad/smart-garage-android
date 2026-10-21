package io.github.conchad.smartgarage.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.Account
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.feature.dashboard.DashboardScreen
import io.github.conchad.smartgarage.feature.more.MoreScreen
import io.github.conchad.smartgarage.feature.more.SectionPlaceholderScreen
import io.github.conchad.smartgarage.feature.profiles.ProfilesScreen
import io.github.conchad.smartgarage.feature.settings.SettingsScreen
import io.github.conchad.smartgarage.feature.setup.SetupFlow
import io.github.conchad.smartgarage.feature.setup.SetupMode
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.EmptyState

@Composable
fun MainShell(profile: ServerProfile, account: Account) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination == null || TopLevel.entries.any { destination.isOn(it) }

    Scaffold(
        // Screens draw their own top bars; this Scaffold only reserves room for the bottom bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                NavigationBar {
                    TopLevel.entries.forEach { item ->
                        val selected = destination?.isOn(item) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(item) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<DashboardRoute> {
                DashboardScreen(
                    profile = profile,
                    account = account,
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<VehiclesRoute> {
                ComingSoon(R.string.nav_vehicles, R.string.section_vehicles_desc, Icons.Outlined.DirectionsCar)
            }
            composable<MaintenanceRoute> {
                ComingSoon(R.string.nav_maintenance, R.string.section_maintenance_desc, Icons.Outlined.Build)
            }
            composable<FuelRoute> {
                ComingSoon(R.string.nav_fuel, R.string.section_fuel_desc, Icons.Outlined.LocalGasStation)
            }
            composable<MoreRoute> {
                MoreScreen(
                    onOpenSection = { navController.navigate(SectionRoute(it)) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<SectionRoute> { entry ->
                SectionPlaceholderScreen(section = entry.toRoute<SectionRoute>().section, onBack = navController::popBackStack)
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = navController::popBackStack,
                    onManageProfiles = { navController.navigate(ProfilesRoute) },
                )
            }
            composable<ProfilesRoute> {
                ProfilesScreen(
                    onBack = navController::popBackStack,
                    onAddProfile = { navController.navigate(AddProfileRoute) },
                )
            }
            composable<AddProfileRoute> {
                // On success the new profile becomes active and AppRoot rebuilds the shell for it.
                SetupFlow(mode = SetupMode.AddProfile, onCancel = navController::popBackStack)
            }
        }
    }
}

@Composable
private fun ComingSoon(title: Int, description: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    AppScaffold(title = stringResource(title)) { padding ->
        EmptyState(
            icon = icon,
            title = stringResource(R.string.coming_soon_title),
            body = stringResource(description),
            modifier = Modifier.padding(padding),
        )
    }
}

private fun NavDestination.isOn(item: TopLevel): Boolean = hierarchy.any { it.hasRoute(item.route::class) }

private fun NavHostController.navigateTopLevel(item: TopLevel) {
    navigate(item.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

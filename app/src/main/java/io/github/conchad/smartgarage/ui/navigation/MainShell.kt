package io.github.conchad.smartgarage.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
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
import io.github.conchad.smartgarage.feature.documents.DocumentFormScreen
import io.github.conchad.smartgarage.feature.expenses.ExpenseFormScreen
import io.github.conchad.smartgarage.feature.fuel.FuelEntryScreen
import io.github.conchad.smartgarage.feature.fuel.FuelScreen
import io.github.conchad.smartgarage.feature.maintenance.MaintenanceScreen
import io.github.conchad.smartgarage.feature.maintenance.RecordFormScreen
import io.github.conchad.smartgarage.feature.maintenance.ScheduleDefaultsScreen
import io.github.conchad.smartgarage.feature.maintenance.ScheduleFormScreen
import io.github.conchad.smartgarage.feature.notes.NoteFormScreen
import io.github.conchad.smartgarage.feature.vehicles.InspectionFormScreen
import io.github.conchad.smartgarage.feature.vehicles.InsuranceFormScreen
import io.github.conchad.smartgarage.feature.vehicles.VehicleActions
import io.github.conchad.smartgarage.feature.vehicles.VehicleDetailScreen
import io.github.conchad.smartgarage.feature.vehicles.VehicleFormScreen
import io.github.conchad.smartgarage.feature.vehicles.VehiclesScreen
import io.github.conchad.smartgarage.feature.more.MoreScreen
import io.github.conchad.smartgarage.feature.sections.SectionActions
import io.github.conchad.smartgarage.feature.sections.SectionScreen
import io.github.conchad.smartgarage.feature.wishlist.WishlistFormScreen
import io.github.conchad.smartgarage.feature.profiles.ProfilesScreen
import io.github.conchad.smartgarage.feature.settings.SettingsScreen
import io.github.conchad.smartgarage.feature.setup.SetupFlow
import io.github.conchad.smartgarage.feature.setup.SetupMode
import io.github.conchad.smartgarage.ui.components.LocalPhotoLoader
import io.github.conchad.smartgarage.ui.components.PhotoLoader
import io.github.conchad.smartgarage.ui.glass.AuroraBackground
import io.github.conchad.smartgarage.ui.glass.LocalAuroraTint
import io.github.conchad.smartgarage.ui.glass.MorphingBottomNav
import io.github.conchad.smartgarage.ui.glass.NavTab

@Composable
fun MainShell(profile: ServerProfile, account: Account, shell: ShellViewModel = hiltViewModel()) {
    // Pull changes every time the app comes to the foreground (D-A13); the hourly worker covers the background.
    LifecycleStartEffect(profile.id) {
        shell.syncNow(profile.id)
        onStopOrDispose {}
    }
    val photoLoader = remember(profile.id) { PhotoLoader(profile.id, shell.photos) }

    CompositionLocalProvider(LocalPhotoLoader provides photoLoader) {
        Shell(profile, account)
    }
}

@Composable
private fun Shell(profile: ServerProfile, account: Account) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination == null || TopLevel.entries.any { destination.isOn(it) }
    val selectedTab = TopLevel.entries.indexOfFirst { destination?.isOn(it) == true }
    // The glass screens (D-A24, D-A26: Home, Vehicles and the vehicle page) draw on the shell's aurora, which also
    // runs behind the floating bottom bar; the other routes keep their plain surfaces, so the aurora isn't composed
    // (and doesn't animate) there.
    val glassRoute = destination == null || destination.isOn(TopLevel.Dashboard) || destination.isOn(TopLevel.Vehicles) ||
        destination.hasRoute<VehicleDetailRoute>()
    val auroraTint = remember { mutableStateOf<Color?>(null) }
    val tabs = TopLevel.entries.map { NavTab(stringResource(it.label), it.icon, it.selectedIcon) }

    CompositionLocalProvider(LocalAuroraTint provides auroraTint) {
        Box(Modifier.fillMaxSize()) {
            if (glassRoute) {
                AuroraBackground(tint = auroraTint.value ?: MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize())
            }
            Scaffold(
                // Screens draw their own top bars; this Scaffold only reserves room for the bottom bar.
                contentWindowInsets = WindowInsets(0),
                containerColor = if (glassRoute) Color.Transparent else MaterialTheme.colorScheme.background,
                bottomBar = {
                    AnimatedVisibility(
                        visible = showBottomBar,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        MorphingBottomNav(
                            tabs = tabs,
                            selectedIndex = selectedTab,
                            onSelect = { navController.navigateTopLevel(TopLevel.entries[it]) },
                        )
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
                            onOpenVehicle = { navController.navigate(VehicleDetailRoute(it)) },
                            onOpenMaintenance = { navController.navigateTopLevel(TopLevel.Maintenance) },
                            onOpenFuel = { navController.navigateTopLevel(TopLevel.Fuel) },
                            onLogService = { navController.navigate(RecordFormRoute(scheduleId = it)) },
                        )
                    }
                    composable<VehiclesRoute> {
                        VehiclesScreen(
                            onOpenVehicle = { navController.navigate(VehicleDetailRoute(it)) },
                            onAddVehicle = { navController.navigate(VehicleFormRoute()) },
                        )
                    }
                    composable<VehicleDetailRoute> {
                        val vehicleActions = remember(navController) {
                            VehicleActions(
                                onLogFillUp = { navController.navigate(FuelEntryRoute(vehicleId = it)) },
                                onLogService = { navController.navigate(RecordFormRoute(scheduleId = it)) },
                                onEditRecord = { navController.navigate(RecordFormRoute(recordId = it)) },
                                onAddSchedule = { navController.navigate(ScheduleFormRoute(vehicleId = it)) },
                                onEditSchedule = { navController.navigate(ScheduleFormRoute(scheduleId = it)) },
                                onEditScheduleDefaults = { navController.navigate(ScheduleDefaultsRoute(scheduleId = it)) },
                                onAddExpense = { navController.navigate(ExpenseFormRoute(vehicleId = it)) },
                                onAddDocument = { navController.navigate(DocumentFormRoute(vehicleId = it)) },
                                onAttachReceipt = { vehicleId, recordId ->
                                    navController.navigate(DocumentFormRoute(vehicleId = vehicleId, maintenanceRecordId = recordId, capture = true))
                                },
                                onEditVehicle = { navController.navigate(VehicleFormRoute(vehicleId = it)) },
                                onAddInspection = { navController.navigate(InspectionFormRoute(vehicleId = it)) },
                                onEditInspection = { vehicleId, id -> navController.navigate(InspectionFormRoute(vehicleId = vehicleId, inspectionId = id)) },
                                onAddInsurance = { navController.navigate(InsuranceFormRoute(vehicleId = it)) },
                                onEditInsurance = { vehicleId, id -> navController.navigate(InsuranceFormRoute(vehicleId = vehicleId, policyId = id)) },
                            )
                        }
                        VehicleDetailScreen(onBack = navController::popBackStack, actions = vehicleActions)
                    }
                    composable<MaintenanceRoute> {
                        MaintenanceScreen(
                            onLogService = { scheduleId, vehicleId -> navController.navigate(RecordFormRoute(scheduleId = scheduleId, vehicleId = vehicleId)) },
                            onEditRecord = { navController.navigate(RecordFormRoute(recordId = it)) },
                            onAddSchedule = { navController.navigate(ScheduleFormRoute(vehicleId = it)) },
                            onEditSchedule = { navController.navigate(ScheduleFormRoute(scheduleId = it)) },
                            onEditScheduleDefaults = { navController.navigate(ScheduleDefaultsRoute(scheduleId = it)) },
                            onAttachReceipt = { vehicleId, recordId ->
                                navController.navigate(DocumentFormRoute(vehicleId = vehicleId, maintenanceRecordId = recordId, capture = true))
                            },
                        )
                    }
                    composable<FuelRoute> {
                        FuelScreen(onLogFillUp = { navController.navigate(FuelEntryRoute(vehicleId = it)) })
                    }
                    composable<FuelEntryRoute> { FuelEntryScreen(onClose = navController::popBackStack) }
                    composable<RecordFormRoute> { RecordFormScreen(onClose = navController::popBackStack) }
                    composable<ScheduleFormRoute> { ScheduleFormScreen(onClose = navController::popBackStack) }
                    composable<ScheduleDefaultsRoute> { ScheduleDefaultsScreen(onClose = navController::popBackStack) }
                    composable<ExpenseFormRoute> { ExpenseFormScreen(onClose = navController::popBackStack) }
                    composable<NoteFormRoute> { NoteFormScreen(onClose = navController::popBackStack) }
                    composable<WishlistFormRoute> { WishlistFormScreen(onClose = navController::popBackStack) }
                    composable<VehicleFormRoute> { VehicleFormScreen(onClose = navController::popBackStack) }
                    composable<InspectionFormRoute> { InspectionFormScreen(onClose = navController::popBackStack) }
                    composable<InsuranceFormRoute> { InsuranceFormScreen(onClose = navController::popBackStack) }
                    composable<DocumentFormRoute> { DocumentFormScreen(onClose = navController::popBackStack) }
                    composable<MoreRoute> {
                        MoreScreen(
                            onOpenSection = { navController.navigate(SectionRoute(it)) },
                            onOpenSettings = { navController.navigate(SettingsRoute) },
                        )
                    }
                    composable<SectionRoute> { entry ->
                        val sectionActions = remember(navController) {
                            SectionActions(
                                onOpenVehicle = { navController.navigate(VehicleDetailRoute(it)) },
                                onAddSchedule = { navController.navigate(ScheduleFormRoute(vehicleId = it)) },
                                onEditSchedule = { navController.navigate(ScheduleFormRoute(scheduleId = it)) },
                                onEditScheduleDefaults = { navController.navigate(ScheduleDefaultsRoute(scheduleId = it)) },
                                onLogService = { navController.navigate(RecordFormRoute(scheduleId = it)) },
                                onAddExpense = { navController.navigate(ExpenseFormRoute(vehicleId = it)) },
                                onAddDocument = { navController.navigate(DocumentFormRoute(vehicleId = it)) },
                                onAttachReceipt = { vehicleId, recordId ->
                                    navController.navigate(DocumentFormRoute(vehicleId = vehicleId, maintenanceRecordId = recordId, capture = true))
                                },
                                onNewNote = { navController.navigate(NoteFormRoute(vehicleId = it)) },
                                onEditNote = { navController.navigate(NoteFormRoute(noteId = it)) },
                                onAddWishlistItem = { navController.navigate(WishlistFormRoute(vehicleId = it)) },
                                onEditWishlistItem = { navController.navigate(WishlistFormRoute(itemId = it)) },
                            )
                        }
                        SectionScreen(
                            section = entry.toRoute<SectionRoute>().section,
                            onBack = navController::popBackStack,
                            actions = sectionActions,
                        )
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

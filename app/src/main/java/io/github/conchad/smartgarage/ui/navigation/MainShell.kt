package io.github.conchad.smartgarage.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
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
import io.github.conchad.smartgarage.core.push.PushTarget
import io.github.conchad.smartgarage.feature.dashboard.DashboardActions
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
import io.github.conchad.smartgarage.feature.vehicles.QuickInfoActions
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
import io.github.conchad.smartgarage.ui.components.LocalBottomBarInset
import io.github.conchad.smartgarage.ui.components.LocalPhotoLoader
import io.github.conchad.smartgarage.ui.components.PhotoLoader
import io.github.conchad.smartgarage.ui.glass.AuroraBackground
import io.github.conchad.smartgarage.ui.glass.AuroraTintHolder
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.LocalAuroraTint
import io.github.conchad.smartgarage.ui.glass.LocalNavAnimatedScope
import io.github.conchad.smartgarage.ui.glass.LocalSharedTransitionScope
import io.github.conchad.smartgarage.ui.glass.LocalVehicleHeroOrigins
import io.github.conchad.smartgarage.ui.glass.VehicleHeroOrigins
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.MorphingBottomNav
import io.github.conchad.smartgarage.ui.glass.NavTab
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion

@Composable
fun MainShell(profile: ServerProfile, account: Account, shell: ShellViewModel = hiltViewModel()) {
    // Pull changes every time the app comes to the foreground (D-A13); the hourly worker covers the background.
    LifecycleStartEffect(profile.id) {
        shell.syncNow(profile.id)
        onStopOrDispose {}
    }
    val photoLoader = remember(profile.id) { PhotoLoader(profile.id, shell.photos) }

    CompositionLocalProvider(LocalPhotoLoader provides photoLoader) {
        Shell(profile, account, shell)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Shell(profile: ServerProfile, account: Account, shell: ShellViewModel) {
    val navController = rememberNavController()
    // A tapped notification (D-A29): PushTapRouter made its profile active, so this shell is the one to open it.
    val pendingPush by shell.pendingPush.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPush) {
        val target = pendingPush ?: return@LaunchedEffect
        if (target.profileId != profile.id) return@LaunchedEffect
        shell.pushOpened(target)
        when (val route = target.route()) {
            is TopLevel -> navController.navigateTopLevel(route)
            else -> navController.navigate(route)
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination == null || TopLevel.entries.any { destination.isOn(it) }
    val selectedTab = TopLevel.entries.indexOfFirst { destination?.isOn(it) == true }
    // Every route is a glass route (D-A30): the shell's one aurora runs behind all of them and the floating bottom
    // bar, with its RESUMED / reduced-motion guards. Only on Home, Vehicles and the vehicle page does a screen's own
    // AuroraTint (the car's color) count; every other destination takes the tab's fallback unconditionally, decided
    // here from the destination so it changes on every navigation (D-A32). The holder's owner-token clear used to be
    // the only thing taking the car's color away, and it fires only when NavHost drops the outgoing entry, which it
    // may keep (an interrupted cross-fade, predictive back, an entry restored by a tab) — so a tab could stay in the
    // Home car's color. What is drawn is recorded in the holder, so the plates and the fan agree with the aurora.
    val auroraTint = remember { AuroraTintHolder() }
    val fallbackTint = fallbackTint(destination)
    val tint = if (destination.honoursScreenTint()) auroraTint.color ?: fallbackTint else fallbackTint
    SideEffect { auroraTint.shown = tint }
    val tabs = TopLevel.entries.map { NavTab(stringResource(it.label), it.icon, it.selectedIcon) }
    val heroOrigins = remember { VehicleHeroOrigins() }
    val reduced = rememberReducedMotion()

    CompositionLocalProvider(LocalAuroraTint provides auroraTint, LocalVehicleHeroOrigins provides heroOrigins) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(tint = tint, modifier = Modifier.fillMaxSize())
            Scaffold(
                // Screens draw their own top bars; this Scaffold only measures the bottom bar.
                contentWindowInsets = WindowInsets(0),
                containerColor = Color.Transparent,
                bottomBar = {
                    AnimatedVisibility(
                        visible = showBottomBar,
                        enter = (expandVertically(tween(Motion.SHORT_MS)) + fadeIn(tween(Motion.SHORT_MS))).orNone(reduced),
                        exit = (shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.QUICK_MS))).orNone(reduced),
                    ) {
                        MorphingBottomNav(
                            tabs = tabs,
                            selectedIndex = selectedTab,
                            onSelect = { navController.navigateTopLevel(TopLevel.entries[it]) },
                        )
                    }
                },
            ) { padding ->
                // D-A40: the content runs full height, under the liquid-glass tray, so the glass has something to
                // frost. The tray's height (navigation bar included) goes to the screens as LocalBottomBarInset, which
                // AppScaffold folds into its bottom inset: each tab's list takes it as contentPadding (its last item
                // still scrolls clear) and the FAB sits above the tray. Nothing is consumed here: Scaffold excludes
                // consumed insets from its own, which would cancel the tray back out.
                // The vehicle hero (D-A34): Home's car card and the vehicle page's photo card are one shared element.
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalSharedTransitionScope provides this,
                        LocalBottomBarInset provides padding.calculateBottomPadding(),
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = DashboardRoute,
                            modifier = Modifier.fillMaxSize(),
                            // The vehicle page and the Vehicles tab bring their own cards in (D-A32, D-A34), so nothing
                            // cross-fades into them: the page is simply there. The screen the vehicle page opens over fades
                            // out in Motion.QUICK_MS while the hero glides and the drawer slides up (the page's own drawer
                            // animation keeps the transition running for Motion.HERO_MS); leaving it, the page fades out the same
                            // way over the screen beneath, which is there at once. Every other navigation keeps NavHost's
                            // 700 ms cross-fade. Timings are Motion's (D-A40); under reduced motion nothing fades.
                            enterTransition = { if (targetState.popsIn() || reduced) EnterTransition.None else fadeIn(tween(Motion.NAV_FADE_MS)) },
                            exitTransition = {
                                when {
                                    reduced -> ExitTransition.None
                                    targetState.destination.hasRoute<VehicleDetailRoute>() -> fadeOut(tween(Motion.QUICK_MS))
                                    targetState.popsIn() -> ExitTransition.None
                                    else -> fadeOut(tween(Motion.NAV_FADE_MS))
                                }
                            },
                            popEnterTransition = {
                                if (targetState.popsIn() || initialState.popsIn() || reduced) EnterTransition.None else fadeIn(tween(Motion.NAV_FADE_MS))
                            },
                            popExitTransition = {
                                when {
                                    reduced -> ExitTransition.None
                                    initialState.popsIn() -> fadeOut(tween(Motion.QUICK_MS))
                                    targetState.popsIn() -> ExitTransition.None
                                    else -> fadeOut(tween(Motion.NAV_FADE_MS))
                                }
                            },
                        ) {
                            composable<DashboardRoute> {
                                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                    DashboardScreen(
                                        profile = profile,
                                        account = account,
                                        onOpenSettings = { navController.navigate(SettingsRoute) },
                                        onOpenVehicle = { navController.navigate(VehicleDetailRoute(it)) },
                                        onOpenMaintenance = { navController.navigateTopLevel(TopLevel.Maintenance) },
                                        onOpenFuel = { navController.navigateTopLevel(TopLevel.Fuel) },
                                        onLogService = { navController.navigate(RecordFormRoute(scheduleId = it)) },
                                        actions = remember(navController) {
                                            DashboardActions(
                                                onLogFillUp = { navController.navigate(FuelEntryRoute(vehicleId = it)) },
                                                onLogService = { navController.navigate(RecordFormRoute(vehicleId = it)) },
                                                onAddExpense = { navController.navigate(ExpenseFormRoute(vehicleId = it)) },
                                                onScanReceipt = { navController.navigate(FuelEntryRoute(vehicleId = it, scan = true)) },
                                                onAddInsurance = { navController.navigate(InsuranceFormRoute(vehicleId = it)) },
                                                onEditInsurance = { vehicleId, id -> navController.navigate(InsuranceFormRoute(vehicleId = vehicleId, policyId = id)) },
                                                onAddInspection = { navController.navigate(InspectionFormRoute(vehicleId = it)) },
                                                onEditInspection = { vehicleId, id -> navController.navigate(InspectionFormRoute(vehicleId = vehicleId, inspectionId = id)) },
                                            )
                                        },
                                    )
                                }
                            }
                            composable<VehiclesRoute> {
                                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                    VehiclesScreen(
                                        onOpenVehicle = { navController.navigate(VehicleDetailRoute(it)) },
                                        onAddVehicle = { navController.navigate(VehicleFormRoute()) },
                                        quickInfoActions = remember(navController) {
                                            QuickInfoActions(
                                                onAddInsurance = { navController.navigate(InsuranceFormRoute(vehicleId = it)) },
                                                onEditInsurance = { vehicleId, id -> navController.navigate(InsuranceFormRoute(vehicleId = vehicleId, policyId = id)) },
                                                onAddInspection = { navController.navigate(InspectionFormRoute(vehicleId = it)) },
                                                onEditInspection = { vehicleId, id -> navController.navigate(InspectionFormRoute(vehicleId = vehicleId, inspectionId = id)) },
                                            )
                                        },
                                    )
                                }
                            }
                            composable<VehicleDetailRoute> {
                                CompositionLocalProvider(LocalNavAnimatedScope provides this) {
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
                            composable<FuelEntryRoute> { entry ->
                                FuelEntryScreen(onClose = navController::popBackStack, scanOnOpen = entry.toRoute<FuelEntryRoute>().scan)
                            }
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
    }
}

private fun NavDestination.isOn(item: TopLevel): Boolean = hierarchy.any { it.hasRoute(item.route::class) }

/** The glass routes whose screen names the aurora's color (D-A24, D-A26): Home, Vehicles and the vehicle page. Null is the first frame, Home. */
private fun NavDestination?.honoursScreenTint(): Boolean =
    this == null || isOn(TopLevel.Dashboard) || isOn(TopLevel.Vehicles) || hasRoute<VehicleDetailRoute>()

/** The routes whose cards bubble in (D-A32) and therefore take no nav cross-fade. */
private fun NavBackStackEntry.popsIn(): Boolean = destination.hasRoute<VehicleDetailRoute>() || destination.hasRoute<VehiclesRoute>()

/**
 * The aurora's color where no screen names one (D-A30): Service drifts in the theme's tertiary, Fuel in the glass
 * gold, More and everything reached from it (sections, Settings, servers, adding a server) in the theme's secondary
 * steel, so the tabs read as different rooms; Home, Vehicles and the forms fall back to primary, which Home and
 * Vehicles replace with the car's color as soon as they have one. With dynamic color on, these follow the wallpaper.
 */
@Composable
private fun fallbackTint(destination: NavDestination?): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        destination == null -> scheme.primary
        destination.isOn(TopLevel.Maintenance) -> scheme.tertiary
        destination.isOn(TopLevel.Fuel) -> glass.sheen
        destination.isOn(TopLevel.More) || destination.hasRoute<SectionRoute>() || destination.hasRoute<SettingsRoute>() ||
            destination.hasRoute<ProfilesRoute>() || destination.hasRoute<AddProfileRoute>() -> scheme.secondary
        else -> scheme.primary
    }
}

/**
 * Where a push lands (PUSH.md §4): the vehicle when the message is about one, else the Service tab for a single
 * schedule, else the section the web `url` names, else Home. Ids were validated when the payload was parsed.
 */
private fun PushTarget.route(): Any = when {
    vehicleId != null -> VehicleDetailRoute(vehicleId)
    scheduleId != null -> TopLevel.Maintenance
    url == "/maintenance" -> TopLevel.Maintenance
    url == "/odometer" -> SectionRoute(Section.Odometer)
    url == "/expenses" -> SectionRoute(Section.Expenses)
    url == "/reminders" -> SectionRoute(Section.Reminders)
    else -> TopLevel.Dashboard
}

private fun NavHostController.navigateTopLevel(item: TopLevel) {
    navigate(item.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

package io.github.conchad.smartgarage.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import io.github.conchad.smartgarage.ui.glass.AuroraLumaHolder
import io.github.conchad.smartgarage.ui.glass.LocalAuroraLuma
import io.github.conchad.smartgarage.ui.glass.navPopEnter
import io.github.conchad.smartgarage.ui.glass.navPopExit
import io.github.conchad.smartgarage.ui.glass.navPushEnter
import io.github.conchad.smartgarage.ui.glass.navPushExit
import io.github.conchad.smartgarage.ui.glass.navTabEnter
import io.github.conchad.smartgarage.ui.glass.navTabExit
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
import io.github.conchad.smartgarage.core.data.withLocalName
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
import io.github.conchad.smartgarage.feature.settings.licenses.OpenSourceLicensesScreen
import io.github.conchad.smartgarage.feature.setup.SetupFlow
import io.github.conchad.smartgarage.feature.setup.SetupMode
import io.github.conchad.smartgarage.feature.textscan.TextScanScreen
import io.github.conchad.smartgarage.ui.components.LocalBottomBarInset
import io.github.conchad.smartgarage.ui.components.LocalPhotoLoader
import io.github.conchad.smartgarage.ui.components.PhotoLoader
import io.github.conchad.smartgarage.ui.glass.AuroraBackground
import io.github.conchad.smartgarage.ui.glass.AuroraTintHolder
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.LocalGlassTokens
import io.github.conchad.smartgarage.ui.glass.legibleOnPanels
import io.github.conchad.smartgarage.ui.glass.underHeroFadeSpec
import io.github.conchad.smartgarage.ui.glass.LocalAuroraTint
import io.github.conchad.smartgarage.ui.glass.LocalAuroraChoices
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
        // The name set in Settings replaces the server's for the greeting and avatar (D-A44).
        Shell(profile, account.withLocalName(profile.localName), shell)
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
    // D-A45: going to or coming back from the vehicle page, the tray fades over the hero glide instead of shrinking.
    // Decided once per destination change, from the destination before it.
    val lastDestination = remember { arrayOfNulls<NavDestination>(1) }
    val barUnderHero = remember(destination) {
        destination?.hasRoute<VehicleDetailRoute>() == true || lastDestination[0]?.hasRoute<VehicleDetailRoute>() == true
    }
    SideEffect { lastDestination[0] = destination }
    // While the tray fades away over a route that isn't a tab (D-A45), it keeps showing the tab it was on rather
    // than dropping its selection on the first frame.
    val lastTab = remember { intArrayOf(0) }
    val selectedTab = TopLevel.entries.indexOfFirst { destination?.isOn(it) == true }.let { if (it >= 0) it.also { i -> lastTab[0] = i } else lastTab[0] }
    // Every route is a glass route (D-A30): the shell's one aurora runs behind all of them and the floating bottom
    // bar, with its RESUMED / reduced-motion guards. Only on Home, Vehicles and the vehicle page does a screen's own
    // AuroraTint (the car's color) count; every other destination takes the tab's fallback unconditionally, decided
    // here from the destination so it changes on every navigation (D-A32). The holder's owner-token clear used to be
    // the only thing taking the car's color away, and it fires only when NavHost drops the outgoing entry, which it
    // may keep (an interrupted cross-fade, predictive back, an entry restored by a tab) — so a tab could stay in the
    // Home car's color. What is drawn is recorded in the holder, so the plates and the fan agree with the aurora.
    val auroraTint = remember { AuroraTintHolder() }
    // D-A44: a color picked in Settings replaces the per-tab fallbacks too; "match vehicle color" keeps them.
    val auroraChoices by shell.auroraChoices.collectAsStateWithLifecycle()
    val fallbackTint = auroraChoices.appTint?.let { Color(it) } ?: fallbackTint(destination)
    val tint = if (destination.honoursScreenTint()) auroraTint.color ?: fallbackTint else fallbackTint
    SideEffect { auroraTint.shown = tint }
    val tabs = TopLevel.entries.map { NavTab(stringResource(it.label), it.icon, it.selectedIcon) }
    val heroOrigins = remember { VehicleHeroOrigins() }
    // The shell's aurora publishes its colour map here for the adaptive ink on titles and pills (D-A46).
    val auroraLuma = remember { AuroraLumaHolder() }
    val reduced = rememberReducedMotion()

    CompositionLocalProvider(
        LocalAuroraTint provides auroraTint,
        LocalVehicleHeroOrigins provides heroOrigins,
        LocalAuroraChoices provides auroraChoices,
        LocalAuroraLuma provides auroraLuma,
    ) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackground(tint = tint, modifier = Modifier.fillMaxSize(), luma = auroraLuma)
            // D-A70: the screens' glass panels and their text colours made legible over this aurora (the same tokens
            // where they already are); the aurora itself keeps the theme's.
            val baseTokens = glass
            val auroraMap = auroraLuma.map
            val panelTokens = remember(baseTokens, auroraMap) { baseTokens.legibleOnPanels(auroraMap) }
            CompositionLocalProvider(LocalGlassTokens provides panelTokens) {
                Scaffold(
                    // Screens draw their own top bars; this Scaffold only measures the bottom bar.
                    contentWindowInsets = WindowInsets(0),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showBottomBar,
                            enter = when {
                                barUnderHero -> fadeIn(underHeroFadeSpec())
                                else -> expandVertically(tween(Motion.SHORT_MS)) + fadeIn(tween(Motion.SHORT_MS))
                            }.orNone(reduced),
                            exit = when {
                                barUnderHero -> fadeOut(underHeroFadeSpec())
                                else -> shrinkVertically(tween(Motion.SHORT_MS)) + fadeOut(tween(Motion.QUICK_MS))
                            }.orNone(reduced),
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
                                // D-A46: the vehicle page opened from a Home card (the hero path, D-A34) is simply there while the
                                // hero glides and its cards pop in, and the screen it opens over fades out over the whole glide
                                // (D-A45); Back to Home reverses that (its cards pop out, D-A43). Tab to tab is a quick
                                // fade-through (D-A51): the old tab is gone before the new one fades in, and nothing pops in.
                                // Every other navigation is plain: the new screen slides in from the right while fading in, and
                                // Back slides it out to the right while fading, revealing the page beneath. Timings are Motion's;
                                // under reduced motion nothing moves.
                                enterTransition = {
                                    when {
                                        reduced || isHeroOpen(heroOrigins) -> EnterTransition.None
                                        isTabSwitch() -> navTabEnter()
                                        else -> navPushEnter()
                                    }
                                },
                                exitTransition = {
                                    when {
                                        reduced -> ExitTransition.None
                                        isHeroOpen(heroOrigins) -> fadeOut(underHeroFadeSpec())
                                        isTabSwitch() -> navTabExit()
                                        else -> navPushExit()
                                    }
                                },
                                popEnterTransition = {
                                    when {
                                        reduced -> EnterTransition.None
                                        isHeroBack() -> fadeIn(underHeroFadeSpec())
                                        isTabSwitch() -> navTabEnter()
                                        else -> navPopEnter()
                                    }
                                },
                                popExitTransition = {
                                    when {
                                        reduced -> ExitTransition.None
                                        // Back from the vehicle page to Home (D-A43): its cards pop out and its bar fades by themselves.
                                        isHeroBack() -> ExitTransition.None
                                        isTabSwitch() -> navTabExit()
                                        else -> navPopExit()
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
                                            onAddVehicle = { navController.navigate(VehicleFormRoute()) },
                                            onOpenMaintenance = { navController.navigateTopLevel(TopLevel.Maintenance) },
                                            onOpenFuel = { navController.navigateTopLevel(TopLevel.Fuel) },
                                            onLogService = { navController.navigate(RecordFormRoute(scheduleId = it)) },
                                            actions = remember(navController) {
                                                DashboardActions(
                                                    onLogFillUp = { navController.navigate(FuelEntryRoute(vehicleId = it)) },
                                                    onLogService = { navController.navigate(RecordFormRoute(vehicleId = it)) },
                                                    onAddExpense = { navController.navigate(ExpenseFormRoute(vehicleId = it)) },
                                                    onScanReceipt = { id, gallery -> navController.navigate(FuelEntryRoute(vehicleId = id, scan = true, gallery = gallery)) },
                                                    onScanText = { navController.navigate(TextScanRoute) },
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
                                composable<VehicleDetailRoute> { entry ->
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
                                        VehicleDetailScreen(
                                            onBack = navController::popBackStack,
                                            actions = vehicleActions,
                                            // Covered by a pushed screen, the page is still the one beneath it; popped, it is neither. Only Back
                                            // to Home is the hero's reverse (D-A46); Back anywhere else slides the page out.
                                            isPopped = {
                                                val current = navController.currentBackStackEntry
                                                entry.id != current?.id && entry.id != navController.previousBackStackEntry?.id &&
                                                    current?.destination?.hasRoute<DashboardRoute>() == true
                                            },
                                        )
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
                                    val route = entry.toRoute<FuelEntryRoute>()
                                    FuelEntryScreen(onClose = navController::popBackStack, scanOnOpen = route.scan, scanFromGallery = route.gallery)
                                }
                                composable<RecordFormRoute> {
                                    RecordFormScreen(
                                        onClose = navController::popBackStack,
                                        // "Save as expense instead" (D-A53): the expense form replaces this one.
                                        onOpenExpense = { route ->
                                            navController.popBackStack()
                                            navController.navigate(route)
                                        },
                                    )
                                }
                                composable<ScheduleFormRoute> { ScheduleFormScreen(onClose = navController::popBackStack) }
                                composable<ScheduleDefaultsRoute> { ScheduleDefaultsScreen(onClose = navController::popBackStack) }
                                composable<ExpenseFormRoute> {
                                    ExpenseFormScreen(
                                        onClose = navController::popBackStack,
                                        // "Log as service record instead" (D-A56): the record form replaces this one.
                                        onOpenRecord = { route ->
                                            navController.popBackStack()
                                            navController.navigate(route)
                                        },
                                    )
                                }
                                composable<NoteFormRoute> { NoteFormScreen(onClose = navController::popBackStack) }
                                composable<WishlistFormRoute> { WishlistFormScreen(onClose = navController::popBackStack) }
                                composable<VehicleFormRoute> { VehicleFormScreen(onClose = navController::popBackStack) }
                                composable<InspectionFormRoute> { InspectionFormScreen(onClose = navController::popBackStack) }
                                composable<InsuranceFormRoute> { InsuranceFormScreen(onClose = navController::popBackStack) }
                                composable<DocumentFormRoute> { DocumentFormScreen(onClose = navController::popBackStack) }
                                composable<TextScanRoute> { TextScanScreen(onClose = navController::popBackStack) }
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
                                        onOpenLicenses = { navController.navigate(LicensesRoute) },
                                    )
                                }
                                composable<LicensesRoute> { OpenSourceLicensesScreen(onBack = navController::popBackStack) }
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
}

private fun NavDestination.isOn(item: TopLevel): Boolean = hierarchy.any { it.hasRoute(item.route::class) }

/** The glass routes whose screen names the aurora's color (D-A24, D-A26): Home, Vehicles and the vehicle page. Null is the first frame, Home. */
private fun NavDestination?.honoursScreenTint(): Boolean =
    this == null || isOn(TopLevel.Dashboard) || isOn(TopLevel.Vehicles) || hasRoute<VehicleDetailRoute>() ||
        // The vehicle form previews the aurora color being picked (D-A44).
        hasRoute<VehicleFormRoute>()

/** Both ends are tabs: the bottom bar's switch (or Back from a tab to Home), a fade-through (D-A51). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    TopLevel.entries.any { initialState.destination.isOn(it) } && TopLevel.entries.any { targetState.destination.isOn(it) }

/** Opening the vehicle page from a Home card: the hero path (D-A34, D-A46). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isHeroOpen(origins: VehicleHeroOrigins): Boolean =
    targetState.destination.hasRoute<VehicleDetailRoute>() && origins.isLaunching(targetState.toRoute<VehicleDetailRoute>().vehicleId)

/** Back from the vehicle page to Home: the hero glides home while the page's cards pop out (D-A43). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isHeroBack(): Boolean =
    initialState.destination.hasRoute<VehicleDetailRoute>() && targetState.destination.hasRoute<DashboardRoute>()

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
            destination.hasRoute<LicensesRoute>() || destination.hasRoute<ProfilesRoute>() || destination.hasRoute<AddProfileRoute>() -> scheme.secondary
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

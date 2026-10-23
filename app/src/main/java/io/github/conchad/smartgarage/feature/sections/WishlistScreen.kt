package io.github.conchad.smartgarage.feature.sections

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.WishlistSheet
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.components.wishlistCategoryLabel
import io.github.conchad.smartgarage.ui.components.wishlistEstimate
import io.github.conchad.smartgarage.ui.components.wishlistStatusLabel

private val wishlistOrder = listOf("PLANNED", "ORDERED", "IDEA", "DONE")

@Composable
internal fun WishlistScreen(onBack: () -> Unit, actions: SectionActions, viewModel: WishlistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val add = { actions.onAddWishlistItem(state.selectedVehicleId) }
    SectionScaffold(
        title = stringResource(R.string.nav_wishlist),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.Outlined.ShoppingBag,
        emptyTitle = stringResource(R.string.wishlist_empty_title),
        floatingActionButton = { if (state.loaded) AddFab(stringResource(R.string.action_add_wishlist_item), onClick = add) },
    ) {
        state.data.groupBy { it.status }
            .toSortedMap(compareBy { wishlistOrder.indexOf(it).let { i -> if (i < 0) wishlistOrder.size else i } })
            .forEach { (status, items) ->
                item(key = status, contentType = "header") { GroupHeader("${wishlistStatusLabel(status)} · ${items.size}") }
                item(key = "$status-list", contentType = "group") {
                    GroupCard {
                        items.forEachIndexed { index, item ->
                            if (index > 0) RowDivider()
                            SectionRow(
                                icon = Icons.Outlined.ShoppingBag,
                                title = item.title,
                                detail = listOfNotNull(
                                    wishlistCategoryLabel(item.category),
                                    state.vehicleById[item.vehicleId]?.shortName?.takeIf { showVehicle },
                                    item.targetDate.toInstantOrNull()?.let(fmt::shortDate),
                                ).joinToString(" · "),
                                onClick = { open = item.id },
                                trailing = item.estimatedCostCents?.let { wishlistEstimate(fmt, it, item.currency) },
                                pending = item.id in pendingIds,
                            )
                        }
                    }
                }
            }
    }
    state.data.firstOrNull { it.id == open }?.let { item ->
        WishlistSheet(
            item,
            state.vehicleById[item.vehicleId],
            fmt,
            onDismiss = { open = null },
            pending = item.id in pendingIds,
            onEdit = {
                open = null
                actions.onEditWishlistItem(item.id)
            },
            onDelete = {
                open = null
                viewModel.deleteWishlistItem(item.id)
            },
        )
    }
}

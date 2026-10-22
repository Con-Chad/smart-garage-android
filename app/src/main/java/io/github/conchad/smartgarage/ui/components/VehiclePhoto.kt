package io.github.conchad.smartgarage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.conchad.smartgarage.core.data.PhotoStore
import io.github.conchad.smartgarage.core.network.InvalidServerIdException
import io.github.conchad.smartgarage.core.model.Vehicle
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a document id to a local file for the active profile; provided by the main shell. Files already resolved
 * are remembered, so a card scrolled back into view starts with its photo instead of the placeholder glyph (D-A23).
 * One loader lives per profile, and a vehicle's photo id changes when the photo does, so the map never goes stale.
 */
class PhotoLoader(private val profileId: String, private val store: PhotoStore) {
    private val resolved = ConcurrentHashMap<String, File>()

    /** The file if it was resolved before, without touching the disk. */
    fun cached(documentId: String): File? = resolved[documentId]

    suspend fun file(documentId: String): File? {
        resolved[documentId]?.let { if (it.exists()) return it else resolved.remove(documentId) }
        // An id the server shouldn't have sent (D-A28) is treated like a photo that can't be fetched: the glyph shows.
        val file = try {
            store.file(profileId, documentId)
        } catch (_: InvalidServerIdException) {
            null
        }
        return file?.also { resolved[documentId] = it }
    }
}

val LocalPhotoLoader = staticCompositionLocalOf<PhotoLoader?> { null }

/**
 * The vehicle's photo, or a tinted car glyph while it loads or when there is none. Photos come from the local
 * cache first, so they show offline.
 */
@Composable
fun VehiclePhoto(vehicle: Vehicle, modifier: Modifier = Modifier, shape: Shape = RectangleShape, iconSize: Dp = 40.dp) {
    val loader = LocalPhotoLoader.current
    val documentId = vehicle.imageDocumentId
    val file by produceState(documentId?.let { loader?.cached(it) }, documentId, loader) {
        value = documentId?.let { id -> loader?.file(id) }
    }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.secondaryContainer, colors.surfaceContainerHighest))),
        contentAlignment = Alignment.Center,
    ) {
        if (file == null) {
            Icon(
                Icons.Outlined.DirectionsCar,
                contentDescription = null,
                tint = colors.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(iconSize),
            )
        } else {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

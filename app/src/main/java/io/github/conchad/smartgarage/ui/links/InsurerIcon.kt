package io.github.conchad.smartgarage.ui.links

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launcher icon of the insurer's app for a policy's provider (D-A31), or null when the provider matches no
 * entry in [InsuranceApps] or the app isn't installed. The icon is rasterised once per package at 24 dp on the IO
 * dispatcher (`PackageManager.getApplicationIcon` inflates adaptive icons, which is not main-thread work) and kept
 * in [InsurerIcons] for the process, so a card that recomposes or a second screen never loads it again.
 */
@Composable
fun rememberInsurerIcon(provider: String?): ImageBitmap? {
    val packageName = remember(provider) { InsuranceApps.packageFor(provider) } ?: return null
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val icon by produceState(InsurerIcons.cached(packageName), packageName, sizePx) {
        if (value == null) value = withContext(Dispatchers.IO) { InsurerIcons.load(context, packageName, sizePx) }
    }
    return icon
}

/** Rendered size of the icon on a row or tile. */
val ICON_SIZE = 24.dp

/** Process-wide cache of rasterised insurer icons by package name; a few small bitmaps at most. */
object InsurerIcons {
    private val cache = HashMap<String, ImageBitmap>()

    @Synchronized
    fun cached(packageName: String): ImageBitmap? = cache[packageName]

    /** Loads and caches the icon; null (and nothing cached) when the package isn't installed or visible. */
    fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1)).asImageBitmap()
        }.getOrNull() ?: return null
        synchronized(this) { cache[packageName] = bitmap }
        return bitmap
    }
}

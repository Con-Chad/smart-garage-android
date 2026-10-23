package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.ui.components.RowDivider

/*
 * Loading placeholders shaped like the content they stand for (D-A41): grey "bones" in the glass pill colour with a
 * faint band sweeping across them, instead of a centred spinner. The sweep is one infinite transition per skeleton,
 * read only inside the bones' draw lambdas, so it repaints the bones and recomposes nothing. Under reduced motion
 * (or off screen) the bones are still and the band is gone.
 */

/** The sweep's phase, shared by every bone of one skeleton; null when the bones should stand still. */
private val LocalShimmer = staticCompositionLocalOf<State<Float>?> { null }

/** Provides one sweep for every [SkeletonBone] inside [content]. */
@Composable
fun ShimmerHost(content: @Composable () -> Unit) {
    val moving = rememberAmbientMotion()
    val phase = if (moving) {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "shimmer-phase",
        )
    } else {
        null
    }
    CompositionLocalProvider(LocalShimmer provides phase, content = content)
}

/**
 * One placeholder block: [modifier] gives it its size. The fill is the glass pill colour; while a [ShimmerHost]
 * sweeps, a soft white band crosses it once per [Motion.SHIMMER_MS], travelling three bone-widths so it is visible a
 * third of the time and the bones rest between passes.
 */
@Composable
fun SkeletonBone(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    val tokens = glass
    val phase = LocalShimmer.current
    Box(
        modifier.drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val band = size.width.coerceAtLeast(1f)
            val brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = SHIMMER_ALPHA),
                1f to Color.Transparent,
                startX = 0f,
                endX = band,
            )
            onDrawBehind {
                clipPath(path) {
                    drawRect(tokens.pill)
                    val p = phase?.value ?: return@clipPath
                    // -1 → 2 widths: the band enters from the left, leaves to the right, then rests off the bone.
                    val x = (p * 3f - 1f) * band
                    translate(left = x) { drawRect(brush, topLeft = Offset.Zero, size = size) }
                }
            }
        },
    )
}

/** The band's peak opacity: a hint of light, so the bones read as "coming" without flashing. */
private const val SHIMMER_ALPHA = 0.08f

/**
 * A list screen's loading placeholder: [groups] group headers, each over a glass card of [rows] list rows (an icon,
 * a title line, a detail line, a value on the right), laid out with the real rows' paddings so nothing jumps when
 * the content cross-fades in. [caption] (the first sync's "Syncing your garage…") sits above in the haloed glass
 * text. TalkBack reads one polite "Loading" for the whole block.
 */
@Composable
fun SkeletonList(label: String, modifier: Modifier = Modifier, caption: String? = null, groups: Int = 2, rows: Int = 3) {
    val tokens = glass
    ShimmerHost {
        Column(
            modifier
                .fillMaxSize()
                .clearAndSetSemantics {
                    contentDescription = caption ?: label
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.onAurora(tokens),
                    color = tokens.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            repeat(groups) { group ->
                // GroupHeader's paddings: 24 dp above, 8 below, a titleMedium line with 12 dp of room each side.
                SkeletonBone(
                    Modifier
                        .padding(start = 16.dp, top = 36.dp, bottom = 20.dp)
                        .width(if (group % 2 == 0) 140.dp else 104.dp)
                        .height(18.dp),
                )
                GlassCard(elevated = false) {
                    repeat(rows) { row ->
                        if (row > 0) RowDivider()
                        SkeletonRow(wide = (row + group) % 2 == 0)
                    }
                }
            }
        }
    }
}

/** One placeholder list row with SectionRow's 16 / 14 dp paddings. */
@Composable
private fun SkeletonRow(wide: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBone(Modifier.size(28.dp), CircleShape)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBone(Modifier.fillMaxWidth(if (wide) 0.75f else 0.55f).height(14.dp))
            SkeletonBone(Modifier.fillMaxWidth(if (wide) 0.5f else 0.65f).height(11.dp))
        }
        SkeletonBone(Modifier.width(52.dp).height(14.dp))
    }
}

/**
 * A form's loading placeholder, inside the form's glass panel: [fields] outlined-field shapes (56 dp, the fields'
 * small radius), with a section-title bone before the third, as the editors group their fields. TalkBack reads one polite "Loading".
 */
@Composable
fun SkeletonFields(label: String, modifier: Modifier = Modifier, fields: Int = 4) {
    ShimmerHost {
        Column(
            modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(fields) { i ->
                if (i == 2) {
                    SkeletonBone(Modifier.width(120.dp).height(16.dp))
                }
                SkeletonBone(Modifier.fillMaxWidth().height(56.dp), MaterialTheme.shapes.extraSmall)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

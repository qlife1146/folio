package com.mccal.folio

import android.view.View
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** One saved material controls the launcher's translucent surfaces. */
enum class BackgroundMaterial(
    @StringRes val label: Int,
    val blurRadiusDp: Float,
    val fillAlpha: Float,
    val edgeAlpha: Float,
    val sheenAlpha: Float,
    val scrimAlpha: Float,
) {
    LIQUID_GLASS(R.string.material_liquid_glass, 6f, .14f, .38f, .22f, .24f),
    SOFT_BLUR(R.string.material_soft_blur, 16f, .28f, .20f, .10f, .42f),
    STRONG_BLUR(R.string.material_strong_blur, 28f, .52f, .14f, .04f, .52f),
}

internal val LocalBackgroundMaterial = staticCompositionLocalOf { BackgroundMaterial.SOFT_BLUR }
internal val LocalMaterialBackdrop = staticCompositionLocalOf<MaterialBackdrop?> { null }

/** The same blur is used by Home and by a popup covered by another popup. */
@Composable
internal fun Modifier.popupBackdropBlur(enabled: Boolean): Modifier {
    val radius = with(LocalDensity.current) { LocalBackgroundMaterial.current.blurRadiusDp.dp.toPx() }
    val effect = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    return this.then(Modifier.graphicsLayer { renderEffect = if (enabled) effect else null })
}

/** Only wallpaper is recorded here; foreground consumers must stay outside the capture. */
internal class MaterialBackdrop(val original: GraphicsLayer, val blurred: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
    var root: View? = null
    var ready by mutableStateOf(false)
}

@Composable
internal fun rememberMaterialBackdrop(): MaterialBackdrop {
    val original = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(original, blurred) { MaterialBackdrop(original, blurred) }
}

internal fun Modifier.captureMaterialBackdrop(enabled: Boolean = true): Modifier = composed {
    val backdrop = LocalMaterialBackdrop.current ?: return@composed this
    val material = LocalBackgroundMaterial.current
    val root = LocalView.current.rootView
    val radius = with(LocalDensity.current) { material.blurRadiusDp.dp.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    onGloballyPositioned { backdrop.coordinates = it; backdrop.root = root }
        .drawWithContent {
            backdrop.ready = enabled
            if (!enabled) drawContent()
            else {
                backdrop.original.record { this@drawWithContent.drawContent() }
                backdrop.blurred.renderEffect = blur
                backdrop.blurred.record { drawLayer(backdrop.original) }
                drawLayer(backdrop.original)
            }
        }
}

/** Keep the library's layout/input in its pager, but draw it above the blurred launcher. */
internal class LibraryForeground(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var viewport by mutableStateOf(Rect.Zero)
    var panelBounds by mutableStateOf(Rect.Zero)
    var ready by mutableStateOf(false)
}

internal val LocalLibraryForeground = staticCompositionLocalOf<LibraryForeground?> { null }

@Composable
internal fun rememberLibraryForeground(): LibraryForeground {
    val layer = rememberGraphicsLayer()
    return remember(layer) { LibraryForeground(layer) }
}

@Composable
internal fun LibraryForegroundContent(modifier: Modifier, enabled: Boolean, content: @Composable (Modifier) -> Unit) {
    val foreground = LocalLibraryForeground.current
    if (!enabled || foreground == null) {
        content(modifier)
        return
    }
    DisposableEffect(foreground) { onDispose { foreground.ready = false } }
    // Library-specific scale, page effects and padding remain inside the recording. The outer
    // box only follows the pager's position, so replaying it preserves those effects exactly.
    Box(Modifier.fillMaxSize().onGloballyPositioned { foreground.origin = it.positionInRoot() }
        .drawWithContent {
            foreground.layer.record { this@drawWithContent.drawContent() }
            foreground.ready = true
        }) {
        content(modifier)
    }
}

@Composable
internal fun LibraryForegroundOverlay(foreground: LibraryForeground, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Drawing only: touches still reach the original search field, list and drag handlers.
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }.drawWithContent {
        if (foreground.ready && !foreground.viewport.isEmpty) {
            val bounds = foreground.viewport.translate(-origin)
            clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                val offset = foreground.origin - origin
                translate(offset.x, offset.y) { drawLayer(foreground.layer) }
            }
        }
    })
}

private class MaterialPosition {
    // The coordinate object can stay the same while its position changes (scrolling or dragging).
    var coordinates by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())
}

/** Clips the background alone, preserving badges and any foreground drawn outside the surface. */
internal fun Modifier.materialBackground(shape: Shape, tint: Color? = null): Modifier = composed {
    val material = LocalBackgroundMaterial.current
    val backdrop = LocalMaterialBackdrop.current
    val solid = LocalSolidGlass.current
    val root = LocalView.current.rootView
    val position = remember { MaterialPosition() }
    val fill = (tint ?: Glass).copy(alpha = if (solid) 1f else material.fillAlpha)
    val dark = LocalDuoPalette.current.dark
    val edgeColor = if (dark) Color.White else Color.Black
    val edgeAlpha = material.edgeAlpha * if (dark) 1f else .35f
    onGloballyPositioned { position.coordinates = it }.drawWithCache {
        val path = when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Generic -> outline.path
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        }
        val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha = material.sheenAlpha), Color.Transparent),
            start = Offset.Zero, end = Offset(size.width, size.height))
        val edge = Brush.linearGradient(listOf(edgeColor.copy(alpha = edgeAlpha),
            edgeColor.copy(alpha = edgeAlpha * .35f), edgeColor.copy(alpha = edgeAlpha * .7f)))
        val stroke = Stroke(2.dp.toPx())
        onDrawWithContent {
            clipPath(path) {
                val source = backdrop?.coordinates
                val target = position.coordinates
                // Dialogs and accessibility overlays have a different window. Use tint there rather than
                // sampling Home with incompatible coordinates; Android's own wallpaper is also not captured.
                if (!solid && backdrop?.ready == true && backdrop.root === root &&
                    source?.isAttached == true && target?.isAttached == true) {
                    val origin = target.localPositionOf(source, Offset.Zero)
                    translate(origin.x, origin.y) { drawLayer(backdrop.blurred) }
                }
                drawRect(fill)
                if (!solid) drawRect(sheen)
                drawPath(path, edge, style = stroke)
            }
            drawContent()
        }
    }
}

/** Keep legacy readers consistent without overwriting their saved settings. */
internal fun LauncherState.withCommonMaterial(): LauncherState = copy(
    widgetGlass = backgroundMaterial.fillAlpha,
    glassOutline = backgroundMaterial.edgeAlpha,
    statusStyle = statusStyle.copy(railGlass = backgroundMaterial.fillAlpha),
    panelBlur = backgroundMaterial.blurRadiusDp / 32f,
    folderBackdropOpacity = backgroundMaterial.scrimAlpha,
)

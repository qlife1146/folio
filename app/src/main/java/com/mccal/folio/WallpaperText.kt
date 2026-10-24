package com.mccal.folio

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.core.graphics.ColorUtils
import kotlin.math.pow

/** A small, read-only map; the shared wallpaper bitmap is never retained or recycled here. */
@Immutable
internal class WallpaperLuminanceMap private constructor(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val width: Int,
    private val height: Int,
    private val luminance: FloatArray,
) {
    fun prefersDarkText(text: Rect, background: Rect, dimAlpha: Float, previous: Boolean): Boolean? {
        if (text.isEmpty || background.isEmpty) return null
        val area = text.intersect(background)
        if (area.isEmpty) return null
        // Match drawLauncherBackground's integer center crop, including unfolded/rotated screens.
        val destinationWidth = background.width.toInt().coerceAtLeast(1)
        val destinationHeight = background.height.toInt().coerceAtLeast(1)
        val aspect = destinationWidth.toFloat() / destinationHeight
        val cropWidth: Int
        val cropHeight: Int
        if (sourceWidth.toFloat() / sourceHeight > aspect) {
            cropHeight = sourceHeight
            cropWidth = (cropHeight * aspect).toInt().coerceIn(1, sourceWidth)
        } else {
            cropWidth = sourceWidth
            cropHeight = (cropWidth / aspect).toInt().coerceIn(1, sourceHeight)
        }
        val cropLeft = (sourceWidth - cropWidth) / 2
        val cropTop = (sourceHeight - cropHeight) / 2
        val dim = (1f - dimAlpha.coerceIn(0f, 1f)).pow(2.2f)
        var darkScore = 0f
        var lightScore = 0f
        for (row in 0 until 3) for (column in 0 until 9) {
            val x = area.left + area.width * (column + .5f) / 9
            val y = area.top + area.height * (row + .5f) / 3
            val sourceX = cropLeft + (x - background.left) / background.width * cropWidth
            val sourceY = cropTop + (y - background.top) / background.height * cropHeight
            val gridX = (sourceX / sourceWidth * width).toInt().coerceIn(0, width - 1)
            val gridY = (sourceY / sourceHeight * height).toInt().coerceIn(0, height - 1)
            val value = luminance[gridY * width + gridX] * dim
            // Cap very high contrast so one tiny bright/dark patch cannot dominate the entire label.
            darkScore += ((maxOf(value, darkLuminance) + .05f) /
                (minOf(value, darkLuminance) + .05f)).coerceAtMost(7f)
            lightScore += (1.05f / (value + .05f)).coerceAtMost(7f)
        }
        // Keep the last choice near a boundary instead of flickering while icons move.
        return if (previous) darkScore >= lightScore * .85f else darkScore > lightScore * 1.15f
    }

    companion object {
        private val darkLuminance = ColorUtils.calculateLuminance(FolioColors.SecondaryBackground.toArgb()).toFloat()

        fun fromBitmap(bitmap: Bitmap): WallpaperLuminanceMap {
            val width = minOf(128, bitmap.width)
            val height = minOf(128, bitmap.height)
            val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
            return try {
                val pixels = IntArray(width * height)
                small.getPixels(pixels, 0, width, 0, 0, width, height)
                WallpaperLuminanceMap(bitmap.width, bitmap.height, width, height,
                    FloatArray(pixels.size) { ColorUtils.calculateLuminance(pixels[it]).toFloat() })
            } finally {
                if (small !== bitmap) small.recycle()
            }
        }
    }
}

internal val LocalWallpaperDimAlpha = staticCompositionLocalOf { 0f }

private class WallpaperTextBounds {
    var text = Rect.Zero
    var background = Rect.Zero
}

/** Direct-on-wallpaper text; card, popup and third-party widget text keeps its own material colors. */
@Composable
internal fun WallpaperText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    secondary: Boolean = false,
) {
    val base = LocalHomeInk.current
    val map = LocalWallpaperTone.current.luminance
    val dimAlpha = LocalWallpaperDimAlpha.current
    val backdrop = LocalMaterialBackdrop.current
    val root = LocalView.current.rootView
    val bounds = remember { WallpaperTextBounds() }
    var dark by remember { mutableStateOf(base.dark) }
    fun updateInk() {
        dark = if (base.automatic) map?.prefersDarkText(bounds.text, bounds.background, dimAlpha, dark) ?: base.dark else base.dark
    }
    LaunchedEffect(base, map, dimAlpha) { updateInk() }
    val ink = HomeInk(dark, base.automatic)
    Text(text, modifier.onGloballyPositioned { coordinates ->
        bounds.text = coordinates.boundsInRoot()
        bounds.background = backdrop?.takeIf { it.root === root }?.coordinates?.takeIf { it.isAttached }
            ?.boundsInRoot() ?: Rect.Zero
        updateInk()
    }, color = if (secondary) ink.secondary else ink.primary, fontSize = fontSize, lineHeight = lineHeight,
        fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines, overflow = overflow,
        style = TextStyle(shadow = ink.labelShadow))
}

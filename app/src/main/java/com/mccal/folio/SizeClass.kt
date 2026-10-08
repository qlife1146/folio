package com.mccal.folio

import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity

/**
 * How much to scale dp by to judge a size class at the phone's own screen density. Developer options' "Smallest
 * width" and Display size change how many dp a screen reports, but a phone-sized screen should still get the phone
 * layout: a Galaxy Z Fold8 cover set to 600dp was getting the unfolded one (bottom dock, wide margins).
 */
internal val Configuration.classScale: Float
    get() = classScale(densityDpi, DisplayMetrics.DENSITY_DEVICE_STABLE)

/** Regular size (the unfolded screen, tablets), judged at the phone's own density. */
internal fun Configuration.fitsRegularHomeLayout(): Boolean = fitsRegularHomeLayout(screenWidthDp.toFloat(), screenHeightDp.toFloat(), classScale)

/**
 * How many columns Settings shows at once.
 *
 * 1 is portrait or a compact window: one page at a time. 2 is landscape iPad
 * Settings: the list beside the page once there's room for two readable columns. A page
 * opened *from* another page then pushes inside the page column with a back label, as iPad
 * Settings does. 3 keeps the list you came from beside it, and only on a window of [THREE_PANES_DP] or more (DeX,
 * an external display, a large tablet): on the Fold8's inner screen (932 dp) three columns left each one too narrow.
 *
 * [nested] is true when the open page came from a list on another page. [onFold] is true when the phone is half
 * folded with the fold down the screen: the divider belongs on the crease then, and a third column would put one of
 * them across it.
 */
internal fun settingsColumns(
    widthDp: Float,
    heightDp: Float,
    classScale: Float = 1f,
    nested: Boolean = false,
    onFold: Boolean = false,
    keyboardDp: Float = 0f,
): Int = when {
    widthDp <= sizeClassHeightDp(heightDp, keyboardDp) -> 1
    !fitsRegularHomeLayout(widthDp, sizeClassHeightDp(heightDp, keyboardDp), classScale) || widthDp < 700f -> 1
    nested && !onFold && widthDp >= THREE_PANES_DP -> 3
    else -> 2
}

/**
 * The height a size class is judged by while the keyboard is up. A keyboard covers a window; it doesn't make it a
 * smaller one, so [heightDp] (what's left to draw in) plus [keyboardDp] (what the keyboard covers) is the window
 * the layout is owed. Judged on what's left instead, the unfolded screen looks like a phone's the moment you tap a
 * text field, and the pane holding that field is taken away with the layout it belonged to (#117).
 */
internal fun sizeClassHeightDp(heightDp: Float, keyboardDp: Float): Float = heightDp + keyboardDp

/**
 * The keyboard height to give [sizeClassHeightDp] inside FolioSheet's full-screen page, which is where every sheet
 * that measures its own box is drawn. That page pads its content column by navigationBarsPadding() and only then by
 * WindowInsets.ime, and windowInsetsPadding subtracts what an earlier one already consumed, so the IME padding takes
 * off just the part of the keyboard past the navigation bar.
 *
 * Reading the raw WindowInsets.ime here would add the navigation bar back a second time and make the reconstructed
 * window taller than the real one. A window whose content height sits just under [HOME_REGULAR_MIN_HEIGHT_DP] would
 * then turn regular while a field has focus: #117's bug again, pointing the other way.
 */
@Composable
internal fun keyboardDpOverSheet(): Float = with(LocalDensity.current) {
    WindowInsets.ime.exclude(WindowInsets.navigationBars).getBottom(this).toDp().value
}

/**
 * Whether Settings keeps its list beside the page rather than pushing pages over it, given the columns the host
 * allows it ([maxColumns]). The keyboard is measured out, as in [settingsColumns].
 */
internal fun settingsSplits(widthDp: Float, heightDp: Float, classScale: Float = 1f, keyboardDp: Float = 0f, maxColumns: Int = 3): Boolean =
    maxColumns >= 2 && widthDp > sizeClassHeightDp(heightDp, keyboardDp) &&
        fitsRegularHomeLayout(widthDp, sizeClassHeightDp(heightDp, keyboardDp), classScale)

/**
 * The narrowest window that shows three panes at once: a sidebar, a list and the page opened from it. Material's
 * "large" width class. Below it, what you open replaces the list, with Back to return, so the page gets the room.
 */
internal const val THREE_PANES_DP = 1200f

/** The maximum number of columns the Settings host allows. */
internal val LocalSettingsMaxColumns = staticCompositionLocalOf { 3 }

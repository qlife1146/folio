package com.mccal.folio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * What the widget picker is open for: where the widget will go, and whether the list is narrowed to one app.
 *
 * A widget can be headed for a slot on Home, for the Smart Stack at a placement, or for the Today View, and the
 * picker itself can be opened from an app's own menu — pieces of state in [LauncherScreen] that were set in
 * the same breath every time the picker opened. They are saved, so a picker left open across a fold still knows
 * what it was asked for.
 */
@Stable
internal class WidgetRequest(
    slot: Int = 0,
    targetIndex: Int = Int.MIN_VALUE,
    stackSlot: Int? = null,
    toToday: Boolean = false,
    packageName: String? = null,
    profileSerial: Long? = null,
) {
    /** The placement slot the widget will take, and the Home cell it was asked for. */
    var slot by mutableIntStateOf(slot)
    var targetIndex by mutableIntStateOf(targetIndex)

    /** Set while the picker is adding to the Smart Stack at this placement slot. */
    var stackSlot by mutableStateOf(stackSlot)

    /** Set while the picker is adding to the Today View. */
    var toToday by mutableStateOf(toToday)

    /** The app whose widgets are being shown, and which profile's copy of it; null lists everything. */
    var packageName by mutableStateOf(packageName)
    var profileSerial by mutableStateOf(profileSerial)

    /** Show every app's widgets again, at whatever spot was already chosen. */
    fun anyApp() {
        packageName = null
        profileSerial = null
    }

    companion object {
        val Saver = listSaver<WidgetRequest, Any?>(
            save = { listOf(it.slot, it.targetIndex, it.stackSlot, it.toToday, it.packageName, it.profileSerial) },
            restore = {
                // Drop the old fixed-cell flag when restoring a picker saved by an earlier version.
                val values = if (it.size == 7) it.filterIndexed { index, _ -> index != 2 } else it
                WidgetRequest(values[0] as Int, values[1] as Int, values[2] as Int?, values[3] as Boolean,
                    values[4] as String?, values[5] as Long?)
            },
        )
    }
}

@Composable
internal fun rememberWidgetRequest(): WidgetRequest = rememberSaveable(saver = WidgetRequest.Saver) { WidgetRequest() }

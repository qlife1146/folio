package com.mccal.folio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppLibraryIndex(
    letters: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    ink: Color = Color.White,
) {
    if (letters.isEmpty()) return
    val select = rememberUpdatedState(onSelect)
    BoxWithConstraints(modifier.width(28.dp), contentAlignment = Alignment.TopCenter) {
        val itemHeight = if (maxHeight == Dp.Infinity) 18.dp else minOf(18.dp, maxHeight / letters.size.toFloat())
        Column(
            Modifier.fillMaxWidth().height(itemHeight * letters.size.toFloat())
                .pointerInput(letters) {
                    var selected = -1
                    fun selectAt(y: Float) {
                        val index = (y / size.height.coerceAtLeast(1) * letters.size).toInt().coerceIn(letters.indices)
                        if (index != selected) {
                            selected = index
                            select.value(letters[index])
                        }
                    }
                    detectDragGestures(
                        onDragStart = { selected = -1; selectAt(it.y) },
                        onDragEnd = { selected = -1 },
                        onDragCancel = { selected = -1 },
                        onDrag = { change, _ -> change.consume(); selectAt(change.position.y) },
                    )
                },
        ) {
            letters.forEach { letter ->
                Text(
                    letter,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { select.value(letter) }
                        .wrapContentHeight(Alignment.CenterVertically),
                    color = ink,
                    fontSize = minOf(12f, itemHeight.value * .72f).sp,
                    lineHeight = itemHeight.value.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

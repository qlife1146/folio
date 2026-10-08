package com.mccal.folio

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** Continuous corners with straight sides, even on a long dock or activity card. */
internal data class SquircleCornerShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { radius.toPx() }.coerceIn(0f, minOf(w, h).coerceAtLeast(0f) / 2f)
        // Two cubic segments approximate each n=4 superellipse corner and meet the sides smoothly.
        val middle = r * .159104f
        val near = middle * 2f
        val far = r * (4f / 7f)
        return Outline.Generic(Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            cubicTo(w - far, 0f, w - near, 0f, w - middle, middle)
            cubicTo(w, near, w, far, w, r)
            lineTo(w, h - r)
            cubicTo(w, h - far, w, h - near, w - middle, h - middle)
            cubicTo(w - near, h, w - far, h, w - r, h)
            lineTo(r, h)
            cubicTo(far, h, near, h, middle, h - middle)
            cubicTo(0f, h - near, 0f, h - far, 0f, h - r)
            lineTo(0f, r)
            cubicTo(0f, far, 0f, near, middle, middle)
            cubicTo(near, 0f, far, 0f, r, 0f)
            close()
        })
    }
}

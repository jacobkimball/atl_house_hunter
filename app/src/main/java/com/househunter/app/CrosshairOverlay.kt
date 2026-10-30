package com.househunter.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp

/**
 * The placement crosshair (requirements §8.1): a dot with a dotted vertical and horizontal line
 * running through it to the screen edges. Each line is a white halo under a dark line so it reads
 * on both light and dark parts of the map. Draws only; touches pass through to the map.
 */
@Composable
fun CrosshairOverlay(dot: Offset, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 9.dp.toPx()))
        listOf(Color.White to 5.dp.toPx(), Color(0xFF1B1B1B) to 2.dp.toPx()).forEach { (color, width) ->
            drawLine(color, Offset(dot.x, 0f), Offset(dot.x, size.height), strokeWidth = width, pathEffect = dash)
            drawLine(color, Offset(0f, dot.y), Offset(size.width, dot.y), strokeWidth = width, pathEffect = dash)
        }
        drawCircle(Color.White, radius = 12.dp.toPx(), center = dot)
        drawCircle(Color(MarkerLayer.PIN_COLOR), radius = 8.dp.toPx(), center = dot)
    }
}

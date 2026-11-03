package app.ihearyou.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drawn pictograms on a 24-unit grid with square caps: mechanical marks that belong to a printed
 * plate, not a rounded icon font. One weight everywhere.
 */
enum class GlyphKind {
    Now, Keys, Setup, Battery, Signal, Link, Chevron, Back, Plus, Minus, Mute, Warning, Check,
    Close, Search, Refresh, Scan, Mic,
}

@Composable
fun Glyph(
    kind: GlyphKind,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    size: Dp = 20.dp,
    stroke: Float = 1.7f,
) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val u = this.size.minDimension / 24f
        val w = stroke * u
        val strokeStyle = Stroke(width = w, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Square)

        fun rect(x: Float, y: Float, x2: Float, y2: Float, filled: Boolean = false) {
            if (filled) drawRect(color, p(x, y), Size((x2 - x) * u, (y2 - y) * u))
            else drawRect(color, p(x, y), Size((x2 - x) * u, (y2 - y) * u), style = strokeStyle)
        }

        fun path(block: Path.() -> Unit) {
            drawPath(Path().apply(block), color, style = strokeStyle)
        }

        when (kind) {
            GlyphKind.Now -> {
                rect(3.5f, 6.5f, 20.5f, 17.5f)
                line(3.5f, 12f, 20.5f, 12f)
                rect(7f, 10.6f, 13f, 13.4f, filled = true)
            }
            GlyphKind.Mic -> {
                path {
                    moveTo(9f * u, 6.5f * u); lineTo(15f * u, 6.5f * u); lineTo(15f * u, 13f * u)
                    lineTo(9f * u, 13f * u); close()
                }
                line(6f, 11.5f, 6f, 13.5f)
                line(18f, 11.5f, 18f, 13.5f)
                line(6f, 13.5f, 9f, 16.5f)
                line(18f, 13.5f, 15f, 16.5f)
                line(9f, 16.5f, 15f, 16.5f)
                line(12f, 16.5f, 12f, 20.5f)
                line(8.5f, 20.5f, 15.5f, 20.5f)
            }
            GlyphKind.Keys -> {
                rect(4.5f, 5.5f, 19.5f, 18.5f)
                line(4.5f, 14.5f, 19.5f, 14.5f)
                line(9f, 9.6f, 15f, 9.6f)
            }
            GlyphKind.Setup -> {
                drawCircle(color, radius = 6.2f * u, center = p(12f, 12f), style = strokeStyle)
                line(12f, 2.6f, 12f, 5.2f)
                line(12f, 18.8f, 12f, 21.4f)
                line(2.6f, 12f, 5.2f, 12f)
                line(18.8f, 12f, 21.4f, 12f)
            }
            GlyphKind.Battery -> {
                rect(3.5f, 7.5f, 17.5f, 16.5f)
                rect(18.6f, 10.5f, 21f, 13.5f, filled = true)
                rect(6f, 10f, 9f, 14f, filled = true)
            }
            GlyphKind.Signal -> {
                rect(3.5f, 15.5f, 6.5f, 20f, filled = true)
                rect(8.5f, 12.5f, 11.5f, 20f, filled = true)
                rect(13.5f, 9f, 16.5f, 20f, filled = true)
                rect(18.5f, 4.5f, 21.5f, 20f, filled = true)
            }
            GlyphKind.Link -> {
                drawCircle(color, 2.4f * u, p(6.5f, 12f), style = strokeStyle)
                drawCircle(color, 2.4f * u, p(17.5f, 12f), style = strokeStyle)
                line(9f, 12f, 15f, 12f)
            }
            GlyphKind.Chevron -> path {
                moveTo(9f * u, 5f * u); lineTo(16f * u, 12f * u); lineTo(9f * u, 19f * u)
            }
            GlyphKind.Back -> path {
                moveTo(15f * u, 5f * u); lineTo(8f * u, 12f * u); lineTo(15f * u, 19f * u)
            }
            GlyphKind.Plus -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }
            GlyphKind.Minus -> line(5f, 12f, 19f, 12f)
            GlyphKind.Mute -> {
                path {
                    moveTo(3.5f * u, 9.5f * u); lineTo(7f * u, 9.5f * u); lineTo(11f * u, 6f * u)
                    lineTo(11f * u, 18f * u); lineTo(7f * u, 14.5f * u); lineTo(3.5f * u, 14.5f * u)
                    close()
                }
                line(14.5f, 9.5f, 20.5f, 14.5f)
                line(20.5f, 9.5f, 14.5f, 14.5f)
            }
            GlyphKind.Warning -> {
                path {
                    moveTo(12f * u, 3.6f * u); lineTo(21.4f * u, 20f * u); lineTo(2.6f * u, 20f * u)
                    close()
                }
                line(12f, 9.5f, 12f, 14f)
                drawCircle(color, 0.9f * u, p(12f, 17f))
            }
            GlyphKind.Check -> path {
                moveTo(4.5f * u, 12.5f * u); lineTo(9.5f * u, 17.5f * u); lineTo(19.5f * u, 6.5f * u)
            }
            GlyphKind.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            GlyphKind.Search -> {
                drawCircle(color, 6.4f * u, p(10.5f, 10.5f), style = strokeStyle)
                line(15.2f, 15.2f, 20.5f, 20.5f)
            }
            GlyphKind.Refresh -> {
                drawArc(
                    color = color,
                    startAngle = -60f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = p(4f, 4f),
                    size = Size(16f * u, 16f * u),
                    style = strokeStyle,
                )
                path {
                    moveTo(17f * u, 3.5f * u); lineTo(20.5f * u, 7.5f * u); lineTo(15.5f * u, 8.5f * u)
                }
            }
            GlyphKind.Scan -> {
                drawCircle(color, 2f * u, p(12f, 12f), style = strokeStyle)
                drawArc(
                    color, startAngle = 200f, sweepAngle = 70f, useCenter = false,
                    topLeft = p(5f, 5f), size = Size(14f * u, 14f * u), style = strokeStyle,
                )
                drawArc(
                    color, startAngle = 200f, sweepAngle = 70f, useCenter = false,
                    topLeft = p(2f, 2f), size = Size(20f * u, 20f * u), style = strokeStyle,
                )
            }
        }
    }
}

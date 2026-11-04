package app.ihearyou.ui



import android.animation.ValueAnimator
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import app.ihearyou.ui.theme.Space
import kotlin.math.roundToInt

/** Mix two colours in sRGB; used for machined key edges so a filled key keeps its hue. */
fun Color.mix(other: Color, t: Float): Color = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = alpha + (other.alpha - alpha) * t,
)

private fun plateShape() = RoundedCornerShape(2.dp)

/** A screwed plate: fill, hairline border, four corner screws. The base unit of the surface. */
fun Modifier.plate(
    fill: Color,
    line: Color,
    screws: Boolean = false,
    screwColor: Color = line,
): Modifier = this
    .clip(plateShape())
    .background(fill, plateShape())
    .border(1.dp, line, plateShape())
    .drawBehind {
        if (!screws) return@drawBehind
        val r = 1.6.dp.toPx()
        val inset = 5.dp.toPx()
        listOf(
            Offset(inset, inset), Offset(size.width - inset, inset),
            Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset),
        ).forEach { drawCircle(screwColor, radius = r, center = it, alpha = 0.85f) }
    }

/**
 * A titled plate. The title sits on the rule and interrupts it, the way an engraved label breaks
 * the border of a real instrument plate.
 */
@Composable
fun Plate(
    modifier: Modifier = Modifier,
    title: String? = null,
    screws: Boolean = false,
    fillHeight: Boolean = false,
    fill: Color = LocalPanel.current.plate,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPanel.current
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .plate(fill = fill, line = p.rule, screws = screws)
                .padding(contentPadding)
                .padding(top = if (title != null) 8.dp else 0.dp),
        ) {
            content()
        }
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = InstrumentType.plate,
                color = p.inkMuted,
                modifier = Modifier
                    .offset(x = 14.dp, y = (-7).dp)
                    .background(p.ground)
                    .padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalPanel.current.inkMuted) {
    Text(text.uppercase(), style = InstrumentType.plate, color = color, modifier = modifier)
}

@Composable
fun HairRule(modifier: Modifier = Modifier, color: Color = LocalPanel.current.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Vertical hairline separating cells in a ruler. */
@Composable
fun VRule(height: Dp = 28.dp, color: Color = LocalPanel.current.rule) {
    Box(Modifier.width(1.dp).height(height).background(color))
}

enum class KeyTone { Neutral, Primary, Strong, Ghost, Danger }

/**
 * A machined press key: face, hairline border, a two-pixel bottom edge, and a two-pixel travel when
 * pressed. Minimum 48 dp in both axes; never a pill.
 */
@Composable
fun Key(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: KeyTone = KeyTone.Neutral,
    glyph: GlyphKind? = null,
    enabled: Boolean = true,
    dense: Boolean = false,
    contentDescription: String? = null,
) {
    val p = LocalPanel.current
    val label = contentDescription
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel by animateDpAsState(if (pressed) 2.dp else 0.dp, label = "key")

    val face = when (tone) {
        KeyTone.Neutral -> p.plate
        KeyTone.Primary -> p.seal
        KeyTone.Strong -> p.ink
        KeyTone.Ghost -> Color.Transparent
        KeyTone.Danger -> p.fault
    }
    val ink = when (tone) {
        KeyTone.Primary -> p.onSeal
        KeyTone.Strong -> p.plate
        KeyTone.Danger -> p.onFault
        else -> p.ink
    }
    val edge = when (tone) {
        KeyTone.Primary, KeyTone.Danger -> face.mix(Color.Black, 0.32f)
        KeyTone.Strong -> p.ruleStrong
        KeyTone.Ghost -> Color.Transparent
        KeyTone.Neutral -> p.ruleStrong
    }
    val border = if (tone == KeyTone.Ghost) p.rule else edge
    val contentAlpha = if (enabled) 1f else 0.38f

    Box(
        modifier = modifier
            .offset(y = travel)
            .clip(plateShape())
            .background(face, plateShape())
            .border(1.dp, border, plateShape())
            .drawBehind {
                if (tone == KeyTone.Ghost) return@drawBehind
                val h = 2.dp.toPx()
                drawRect(edge, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { onClick() }
            .heightIn(min = 48.dp)
            .padding(
                horizontal = if (dense) 6.dp else 14.dp,
                vertical = if (dense) 4.dp else 8.dp,
            )
            .semantics {
                if (label != null) this.contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        // wraps its content: a Key is only as wide as its label unless the caller passes
        // fillMaxWidth/weight (the Box centres it then). A greedy Key in an unweighted Row
        // used to push its neighbours off screen.
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                Glyph(glyph, color = ink.copy(alpha = contentAlpha), size = if (dense) 16.dp else 18.dp)
                if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
            }
            if (text.isNotEmpty()) {
                FitText(
                    text = if (dense) text.uppercase() else text,
                    style = if (dense) {
                        InstrumentType.plateLarge.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.sp)
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = ink.copy(alpha = contentAlpha),
                    textAlign = TextAlign.Center,
                    maxLines = if (dense) 1 else 2,
                )
            }
        }
    }
}

/**
 * Text that never splits a word: while a line ends inside a word (or one word is wider than the
 * box), the size steps down to [minScale] of the style's size. It stays invisible until it fits,
 * so the broken first layout is never drawn.
 */
@Composable
fun FitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    textAlign: TextAlign? = null,
    minScale: Float = 0.7f,
) {
    var scale by remember(text, style) { mutableStateOf(1f) }
    var ready by remember(text, style) { mutableStateOf(false) }
    val size = style.fontSize * scale
    Text(
        text = text,
        style = style.copy(fontSize = size, lineHeight = if (style.lineHeight.isSp) style.lineHeight * scale else style.lineHeight),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            val broken = (0 until r.lineCount - 1).any { i ->
                val end = r.getLineEnd(i)
                end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
            } || r.hasVisualOverflow
            if (broken && scale > minScale + 0.001f) scale = maxOf(minScale, scale - 0.08f) else ready = true
        },
    )
}

/** A panel toggle: a lever in a well that slides. Reads as hardware, announces as a switch. */
@Composable
fun ToggleKey(
    checked: Boolean,
    label: String,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalPanel.current
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "toggle")
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(p.recess)
                .border(1.dp, if (checked) p.seal else p.ruleStrong, RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (4f + knob * 26f).dp)
                    .size(width = 18.dp, height = 22.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (checked) p.seal else p.plate)
                    .border(1.dp, if (checked) p.seal.mix(Color.Black, 0.35f) else p.ruleStrong),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.ink)
    }
}

/** A row of keys sharing one border, for mutually exclusive settings. */
@Composable
fun SegmentedKeys(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPanel.current
    Row(
        modifier = modifier
            .clip(plateShape())
            .border(1.dp, p.ruleStrong, plateShape()),
    ) {
        options.forEachIndexed { index, (key, label) ->
            if (index > 0) VRule(height = 48.dp, color = p.ruleStrong)
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) p.ink else p.plate)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(key) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = InstrumentType.plateLarge,
                    color = if (isSelected) p.plate else p.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

enum class LampTone { Live, Warn, Fault, Idle }

/**
 * The panel lamp. Filled when the thing it names is true; a ring when it is not. Always paired with
 * a word, never carrying state alone.
 */
@Composable
fun Lamp(
    on: Boolean,
    modifier: Modifier = Modifier,
    tone: LampTone = LampTone.Live,
    pulse: Boolean = false,
    size: Dp = 8.dp,
) {
    val p = LocalPanel.current
    val base = when (tone) {
        LampTone.Live -> p.live
        LampTone.Warn -> p.warn
        LampTone.Fault -> p.fault
        LampTone.Idle -> p.inkMuted
    }
    val reduce = !ValueAnimator.areAnimatorsEnabled()
    val alpha = if (pulse && !reduce) {
        val infinite = rememberInfiniteTransition(label = "lamp")
        infinite.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "lampAlpha",
        ).value
    } else {
        1f
    }
    Canvas(modifier.size(size * 2.2f)) {
        val r = size.toPx() / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        if (on) {
            drawCircle(base, radius = r * 1.55f, center = c, alpha = 0.18f * alpha)
            drawCircle(base, radius = r, center = c, alpha = alpha)
        } else {
            drawCircle(base.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(1.dp.toPx()))
        }
    }
}

/** A rubber-stamp mark: the promise plate of a sealed device. */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, tone: Color = LocalPanel.current.seal) {
    Box(
        modifier = modifier.border(1.dp, tone, RoundedCornerShape(1.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text.uppercase(),
            style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.1.sp, lineHeight = 12.sp),
            color = tone,
        )
    }
}

/** A recessed window holding a live reading, with the label printed beside the figure. */
@Composable
fun ReadingWindow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tone: Color = LocalPanel.current.ink,
) {
    val p = LocalPanel.current
    Row(
        modifier = modifier
            .clip(plateShape())
            .background(p.recess)
            .border(1.dp, p.rule)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label.uppercase(), style = InstrumentType.plate, color = p.inkMuted, modifier = Modifier.padding(bottom = 5.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = InstrumentType.reading, color = tone)
        if (unit != null) {
            Text(unit, style = InstrumentType.plate, color = p.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
    }
}

@Composable
fun SideBadge(side: String, modifier: Modifier = Modifier, tone: Color = LocalPanel.current.ink) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 34.dp, minHeight = 34.dp)
            .border(1.dp, tone, RoundedCornerShape(1.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(side, style = InstrumentType.readingSmall, color = tone)
    }
}

/**
 * Five-cell battery meter over the aid's 1–10 level, two steps per cell. Thresholds for
 * rechargeable aids: 1 empty, 2 or less low, 10 full.
 */
@Composable
fun BatteryMeter(level: Int?, modifier: Modifier = Modifier, hiState: Int? = null) {
    val p = LocalPanel.current
    val filled = level?.coerceIn(1, 10)?.let { (it + 1) / 2 } ?: 0
    val word = batteryWord(androidx.compose.ui.platform.LocalContext.current, level, hiState)
    val tone = when {
        level == null -> p.inkMuted
        level <= 1 -> p.fault
        level <= 2 -> p.warn
        else -> p.ink
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 30.dp, height = 14.dp)) {
            val gap = 2.dp.toPx()
            val cw = (size.width - gap * 4) / 5f
            repeat(5) { i ->
                val x = i * (cw + gap)
                drawRect(
                    color = if (i < filled) tone else p.rule,
                    topLeft = Offset(x, 0f),
                    size = Size(cw, size.height),
                    style = if (i < filled) Fill else Stroke(1.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(word, style = InstrumentType.plate.copy(fontSize = 9.sp), color = tone)
    }
}

/** Four-bar field meter for RSSI. A rendered field, not a decorative sparkline. */
@Composable
fun SignalField(rssi: Int?, modifier: Modifier = Modifier, unit: Boolean = true) {
    val p = LocalPanel.current
    val bars = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -65 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
            val gap = 2.dp.toPx()
            val bw = (size.width - gap * 3) / 4f
            repeat(4) { i ->
                val h = size.height * (0.42f + 0.19f * i)
                drawRect(
                    color = if (i < bars) p.ink else p.rule,
                    topLeft = Offset(i * (bw + gap), size.height - h),
                    size = Size(bw, h),
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = rssi?.let { "$it" } ?: "—",
            style = InstrumentType.number.copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = p.inkMuted,
        )
        if (unit) Text(" dBm", style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, maxLines = 1, softWrap = false)
    }
}

/** Full-width warning plate: code first, then the fix in plain words. */
@Composable
fun NoticeBand(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: Color = LocalPanel.current.fault,
    ink: Color = LocalPanel.current.onFault,
    glyph: GlyphKind = GlyphKind.Warning,
    filled: Boolean = true,
) {
    val p = LocalPanel.current
    val markColor = if (filled) ink else tone
    val bodyColor = if (filled) ink else p.ink
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(plateShape())
            .then(if (filled) Modifier.background(tone) else Modifier.border(1.dp, tone, plateShape()))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Glyph(glyph, color = markColor, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title.uppercase(), style = InstrumentType.plateLarge, color = markColor)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = bodyColor)
        }
    }
}

val LevelTrackHeight = 150.dp

/**
 * The attenuator: a vertical slot with a printed scale and a machined cap. Drag anywhere on the
 * slot; the value is only sent on release. Step keys give the same control to a screen reader.
 */
@Composable
fun LevelFader(
    value: Int?,
    modifier: Modifier = Modifier,
    /** The program's fitted start level, drawn as an index notch beside the scale. */
    mark: Int? = null,
    enabled: Boolean = true,
    trackHeight: Dp? = null,
    contentDescription: String,
    onPreview: (Int) -> Unit = {},
    onCommit: (Int) -> Unit,
) {
    val p = LocalPanel.current
    val label = contentDescription
    var draft by remember { mutableIntStateOf(value ?: 0) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, dragging) {
        if (!dragging && value != null) draft = value
    }
    val fraction = (draft / 255f).coerceIn(0f, 1f)
    val isData = value != null
    val track = if (trackHeight != null) Modifier.height(trackHeight) else Modifier.fillMaxHeight()

    Box(
        modifier = modifier
            .semantics {
                this.contentDescription = label
                if (isData) {
                    progressBarRangeInfo = ProgressBarRangeInfo(current = fraction, range = 0f..1f)
                    setProgress { target ->
                        val v = (target.coerceIn(0f, 1f) * 255f).roundToInt()
                        draft = v
                        onCommit(v)
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().then(track)) {
            val cx = size.width / 2f
            val top = 12.dp.toPx()
            val bottom = size.height - 12.dp.toPx()
            val channel = 10.dp.toPx()
            // recessed channel
            drawRect(p.recess, topLeft = Offset(cx - channel / 2f, top), size = Size(channel, bottom - top))
            drawRect(
                p.ruleStrong, topLeft = Offset(cx - channel / 2f, top),
                size = Size(channel, bottom - top), style = Stroke(1.dp.toPx()),
            )
            // printed scale: the aid's 13 positions, every fourth long
            repeat(13) { i ->
                val y = bottom - (bottom - top) * (i / 12f)
                val long = i % 4 == 0
                val len = if (long) 9.dp.toPx() else 4.dp.toPx()
                val gap = channel / 2f + 3.dp.toPx()
                drawLine(p.ruleStrong, Offset(cx - gap - len, y), Offset(cx - gap, y), 1.dp.toPx())
                drawLine(p.ruleStrong, Offset(cx + gap, y), Offset(cx + gap + len, y), 1.dp.toPx())
            }
            // index notch at the fitted start level, left of the cap's travel
            if (mark != null) {
                val my = bottom - (bottom - top) * (mark.coerceIn(0, 255) / 255f)
                val tip = cx - size.width * 0.35f - 3.dp.toPx() // just outside the cap, so it shows at the default too
                val w = 7.dp.toPx()
                val h = 5.dp.toPx()
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(tip, my)
                        lineTo(tip - w, my - h)
                        lineTo(tip - w, my + h)
                        close()
                    },
                    p.ink,
                )
            }
            // machined cap
            val capH = 22.dp.toPx()
            val capW = size.width * 0.7f
            val cy = bottom - (bottom - top) * fraction
            drawRect(p.ink, topLeft = Offset(cx - capW / 2f, cy - capH / 2f), size = Size(capW, capH))
            drawRect(
                p.plate.copy(alpha = 0.45f),
                topLeft = Offset(cx - capW / 2f + 4.dp.toPx(), cy - capH / 2f + 4.dp.toPx()),
                size = Size(capW - 8.dp.toPx(), 1.dp.toPx()),
            )
            drawRect(
                p.plate.copy(alpha = 0.45f),
                topLeft = Offset(cx - capW / 2f + 4.dp.toPx(), cy + capH / 2f - 5.dp.toPx()),
                size = Size(capW - 8.dp.toPx(), 1.dp.toPx()),
            )
            drawRect(p.seal, topLeft = Offset(cx - capW / 2f, cy - 1.dp.toPx()), size = Size(capW, 2.dp.toPx()))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .then(track)
                .pointerInput(enabled, isData) {
                    if (!enabled || !isData) return@pointerInput
                    val trackH = size.height.toFloat()
                    val topPx = 10.dp.toPx()
                    fun setFrom(y: Float) {
                        val usable = trackH - topPx * 2
                        val f = 1f - ((y - topPx) / usable)
                        val v = (f.coerceIn(0f, 1f) * 255f).roundToInt()
                        if (v != draft) {
                            draft = v
                            onPreview(v)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        setFrom(down.position.y)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            setFrom(change.position.y)
                            change.consume()
                        }
                        dragging = false
                        onCommit(draft)
                    }
                },
        )
    }
}

/** Small drawn press-trace for the three gestures, so a word is never the only signal. */
@Composable
fun GestureTrace(
    kind: app.ihearyou.button.Gesture,
    modifier: Modifier = Modifier,
    color: Color = LocalPanel.current.ink,
) {
    Canvas(modifier.size(width = 34.dp, height = 18.dp)) {
        val midY = size.height / 2f
        val w = 2.dp.toPx()
        drawLine(
            color, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f),
            1.dp.toPx(), cap = StrokeCap.Square,
        )
        when (kind) {
            app.ihearyou.button.Gesture.SHORT ->
                drawRect(color, Offset(size.width * 0.22f, midY - 5.dp.toPx()), Size(w, 10.dp.toPx()))
            app.ihearyou.button.Gesture.LONG ->
                drawRect(
                    color, Offset(size.width * 0.18f, midY - 5.dp.toPx()),
                    Size(size.width * 0.46f, 10.dp.toPx()),
                )
            app.ihearyou.button.Gesture.DOUBLE -> {
                drawRect(color, Offset(size.width * 0.16f, midY - 5.dp.toPx()), Size(w, 10.dp.toPx()))
                drawRect(color, Offset(size.width * 0.38f, midY - 5.dp.toPx()), Size(w, 10.dp.toPx()))
            }
        }
    }
}

/** A numbered step in the first-run plate; the sequence itself carries meaning here. */
@Composable
fun NumberedStep(index: Int, title: String, body: String) {
    val p = LocalPanel.current
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
        Box(
            Modifier.size(26.dp).border(1.dp, p.ruleStrong, RoundedCornerShape(1.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", style = InstrumentType.number, color = p.ink)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text(
                body, style = MaterialTheme.typography.bodySmall, color = p.inkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A printed row in a specification plate: label, hairline leader, value. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    tone: Color = LocalPanel.current.ink,
) {
    val p = LocalPanel.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 34.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = InstrumentType.plate,
            color = p.inkMuted,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .padding(bottom = 2.dp)
                .height(1.dp)
                .background(p.rule.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = if (mono) InstrumentType.mono else MaterialTheme.typography.bodySmall,
            color = tone,
        )
    }
}

/** Panel screw head, used sparingly at plate corners. */
@Composable
fun Screw(color: Color = LocalPanel.current.ruleStrong) {
    Canvas(Modifier.size(6.dp)) {
        drawCircle(color, radius = size.minDimension / 2f, style = Stroke(1.dp.toPx()))
        drawLine(
            color, Offset(1.dp.toPx(), size.height / 2),
            Offset(size.width - 1.dp.toPx(), size.height / 2), 1.dp.toPx(),
        )
    }
}

/**
 * A horizontal trim control with a centre detent: integer steps from [min] to [max], a printed
 * tick per step with the zero tick long, and a machined cap. Drag anywhere on the channel; the
 * value is only sent on release. A screen reader can set it directly.
 */
@Composable
fun TrimSlider(
    label: String,
    value: Int?,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -6,
    max: Int = 6,
    enabled: Boolean = true,
    valueText: (Int) -> String = { if (it > 0) "+$it" else "$it" },
    contentDescription: (Int) -> String = { "$label, ${valueText(it)}" },
) {
    val p = LocalPanel.current
    var draft by remember { mutableIntStateOf(value ?: 0) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, dragging) {
        if (!dragging && value != null) draft = value
    }
    val steps = max - min
    val active = enabled && value != null
    val fraction = ((draft - min).toFloat() / steps).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = InstrumentType.plate,
            color = p.inkMuted,
            modifier = Modifier.width(64.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(44.dp)
                .semantics {
                    this.contentDescription = contentDescription(draft)
                    if (value != null) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = draft.toFloat(), range = min.toFloat()..max.toFloat(), steps = steps - 1,
                        )
                        if (active) setProgress { target ->
                            val v = target.roundToInt().coerceIn(min, max)
                            draft = v
                            onCommit(v)
                            true
                        }
                    }
                }
                .pointerInput(active, min, max) {
                    if (!active) return@pointerInput
                    val inset = 12.dp.toPx()
                    fun stepAt(x: Float): Int {
                        val usable = size.width - inset * 2
                        val f = ((x - inset) / usable).coerceIn(0f, 1f)
                        return min + (f * steps).roundToInt()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = true
                        draft = stepAt(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            draft = stepAt(change.position.x)
                            change.consume()
                        }
                        dragging = false
                        if (draft != value) onCommit(draft)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                val inset = 12.dp.toPx()
                val cy = size.height / 2f
                val channel = 8.dp.toPx()
                val left = inset
                val right = size.width - inset
                val alpha = if (active) 1f else 0.38f
                drawRect(p.recess, topLeft = Offset(left, cy - channel / 2f), size = Size(right - left, channel))
                drawRect(
                    p.ruleStrong.copy(alpha = alpha), topLeft = Offset(left, cy - channel / 2f),
                    size = Size(right - left, channel), style = Stroke(1.dp.toPx()),
                )
                for (i in 0..steps) {
                    val x = left + (right - left) * (i / steps.toFloat())
                    val zero = min + i == 0
                    val len = if (zero) 8.dp.toPx() else 4.dp.toPx()
                    val gap = channel / 2f + 2.dp.toPx()
                    drawLine(p.ruleStrong.copy(alpha = alpha), Offset(x, cy - gap - len), Offset(x, cy - gap), 1.dp.toPx())
                    drawLine(p.ruleStrong.copy(alpha = alpha), Offset(x, cy + gap), Offset(x, cy + gap + len), 1.dp.toPx())
                }
                if (value != null) {
                    val capW = 16.dp.toPx()
                    val capH = 28.dp.toPx()
                    val cx = left + (right - left) * fraction
                    drawRect(p.ink.copy(alpha = alpha), topLeft = Offset(cx - capW / 2f, cy - capH / 2f), size = Size(capW, capH))
                    drawRect(p.seal.copy(alpha = alpha), topLeft = Offset(cx - 1.dp.toPx(), cy - capH / 2f), size = Size(2.dp.toPx(), capH))
                }
            }
        }
        Text(
            if (value == null) "—" else valueText(draft),
            style = InstrumentType.mono,
            color = if (draft != 0 && value != null) p.ink else p.inkMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

/**
 * The app's bottom sheet: a Material modal sheet in the panel's colours, square-cornered like the
 * plates. It keeps its content clear of the navigation bar, closes on a swipe down, a tap on the
 * scrim, or Back, and scrolls when its content is taller than the screen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PanelSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val p = LocalPanel.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.ground,
        contentColor = p.ink,
        scrimColor = p.ink.copy(alpha = 0.46f),
        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .background(p.ruleStrong),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            content = content,
        )
    }
}

package app.ihearyou.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.service.AidInfo
import app.ihearyou.service.AidUiState
import app.ihearyou.service.Cmd
import app.ihearyou.service.LinkPhase
import app.ihearyou.service.LinkState
import app.ihearyou.service.RemoteService
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import kotlinx.coroutines.delay

@Composable
fun NowScreen(
    scope: AidScope,
    onOpenAid: (String) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenFinder: () -> Unit = {},
) {
    val fontScale = LocalDensity.current.fontScale
    val aids = orderedAids(scope.addresses, scope.sides, scope.infos, scope.states)
    val stacked = fontScale >= 1.45f

    LaunchedEffect(scope.addresses, scope.sample) {
        while (true) {
            scope.addresses.forEach { scope.send(Cmd.RefreshRssi(it)) }
            delay(app.ihearyou.domain.Proximity.NORMAL_POLL_MS)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 330.dp && !stacked

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            NowHeader(showFinder = scope.addresses.isNotEmpty(), onOpenFinder = onOpenFinder) {
                LinkRuler(scope = scope, aids = aids)
            }
            if (scope.sample) SampleBand()
            Spacer(Modifier.height(10.dp))
            ReceiverNotice(scope = scope, aids = aids, modifier = Modifier.padding(bottom = 10.dp))
            // an aid still within its automatic reconnect attempts shows "connecting" on the
            // ruler only; the fix appears once they run out or for something to act on
            val unlinked = aids.filter { it.second == null }.map { it.first }
            val stuck = unlinked.filterNot { a ->
                scope.links[a].let { it == null || (it.phase == LinkPhase.CONNECTING && it.recovering) }
            }
            if (stuck.isNotEmpty()) {
                LinkProblemBand(
                    scope = scope,
                    missing = unlinked,
                    total = aids.size,
                    onOpenSetup = onOpenSetup,
                )
                Spacer(Modifier.height(10.dp))
            }

            if (aids.isEmpty()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
                ) {
                    Spacer(Modifier.height(10.dp))
                    EmptyPairPlate(onOpenSetup = onOpenSetup)
                }
            } else if (!sideBySide) {
                // narrow or very large text: plates stacked, fixed fader, the screen scrolls
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        aids.forEach { (address, state) -> ChannelFor(address, state, scope, 176.dp, onOpenAid, Modifier.fillMaxWidth()) }
                    }
                    Spacer(Modifier.height(12.dp))
                    PairKeys(scope = scope, aids = aids)
                    StreamPlate(scope = scope, aids = aids)
                    ProgramsPlate(scope = scope, aids = aids, onOpenAid = onOpenAid)
                }
            } else {
                // The channels take whatever the controls below leave, but never less than a
                // usable fader: past that the whole panel scrolls instead of the fader shrinking.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ChannelsThenControls(
                            viewport = viewport,
                            minChannels = ChannelMinHeight,
                            channels = {
                                Row(
                                    Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    aids.forEach { (address, state) ->
                                        ChannelFor(address, state, scope, null, onOpenAid, Modifier.weight(1f).fillMaxHeight())
                                    }
                                }
                            },
                            controls = {
                                Column {
                                    Spacer(Modifier.height(12.dp))
                                    PairKeys(scope = scope, aids = aids)
                                    StreamPlate(scope = scope, aids = aids)
                                    ProgramsPlate(scope = scope, aids = aids, onOpenAid = onOpenAid)
                                    Spacer(Modifier.height(8.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Header, model, level window, a 120 dp fader, keys and the battery/signal row. */
private val ChannelMinHeight = 340.dp

/**
 * [channels] fill the height [controls] leave in the visible area, but never shrink below
 * [minChannels]; inside a vertical scroll that is the moment the panel starts to scroll.
 */
@Composable
private fun ChannelsThenControls(
    viewport: Dp,
    minChannels: Dp,
    channels: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    // inside the scroll the height is unbounded, so the visible height comes in as [viewport]
    androidx.compose.ui.layout.Layout(
        contents = listOf(channels, controls),
        modifier = Modifier.fillMaxWidth(),
    ) { (channelM, controlM), c ->
        val width = c.maxWidth
        val below = controlM.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        val belowH = below.sumOf { it.height }
        val chH = maxOf(viewport.roundToPx() - belowH, minChannels.roundToPx())
        val top = channelM.map { it.measure(Constraints.fixed(width, chH)) }
        layout(width, chH + belowH) {
            top.forEach { it.place(0, 0) }
            var y = chH
            below.forEach { it.place(0, y); y += it.height }
        }
    }
}

@Composable
private fun ChannelFor(
    address: String,
    state: AidUiState?,
    scope: AidScope,
    track: Dp?,
    onOpenAid: (String) -> Unit,
    modifier: Modifier,
) {
    ChannelPlate(
        address = address,
        state = state,
        info = scope.infos[address],
        side = scope.sides[address],
        name = scope.names[address],
        link = scope.links[address],
        trackHeight = track,
        onOpen = { onOpenAid(address) },
        onSend = scope.send,
        modifier = modifier,
    )
}

@Composable
private fun NowHeader(showFinder: Boolean, onOpenFinder: () -> Unit, status: @Composable () -> Unit) {
    val p = LocalPanel.current
    // wordmark with the link status under it, beside the finder key: one band instead of two
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.now_wordmark),
                style = InstrumentType.wordmark,
                color = p.ink,
            )
            status()
        }
        if (showFinder) {
            Key(
                text = stringResource(R.string.finder_open),
                onClick = onOpenFinder,
                tone = KeyTone.Ghost,
                glyph = GlyphKind.Signal,
                dense = true,
                contentDescription = stringResource(R.string.finder_title),
            )
        }
    }
}

@Composable
private fun SampleBand() {
    val p = LocalPanel.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(p.ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(GlyphKind.Warning, color = p.plate, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.now_sample_band),
            style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            color = p.plate,
        )
    }
}

@Composable
private fun LinkRuler(scope: AidScope, aids: List<Pair<String, AidUiState?>>) {
    val p = LocalPanel.current
    val total = aids.size
    val linked = aids.count { it.second != null }
    val all = total > 0 && linked == total
    val lastRead = aids.mapNotNull { it.second?.confirmedAt }.maxOrNull()
    val word = when {
        total == 0 -> stringResource(R.string.now_no_aids)
        all -> if (total == 2) stringResource(R.string.now_both_linked) else stringResource(R.string.n_of_m_linked, linked, total)
        linked > 0 -> stringResource(R.string.n_of_m_linked, linked, total)
        else -> stringResource(R.string.not_linked)
    }
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(
                on = linked > 0,
                tone = if (all) LampTone.Live else LampTone.Warn,
                pulse = linked > 0 && !all,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                word,
                style = InstrumentType.plate,
                color = if (all) p.ink else p.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (total > 0) {
            Text(
                stringResource(R.string.now_read_at, clockOf(lastRead)),
                style = InstrumentType.plate.copy(fontSize = 9.sp),
                color = p.inkMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChannelPlate(
    address: String,
    state: AidUiState?,
    info: AidInfo?,
    side: String?,
    name: String?,
    link: LinkState?,
    trackHeight: Dp?,
    onOpen: () -> Unit,
    onSend: (Cmd) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val sideLc = sideWord(context, side).lowercase()
    val volume = state?.volume
    var lastAudible by remember(address) { mutableIntStateOf(0xAA) }
    LaunchedEffect(volume) { if (volume != null && volume > 0) lastAudible = volume }
    var commitAt by remember(address) { mutableLongStateOf(0L) }
    LaunchedEffect(commitAt) {
        if (commitAt != 0L) {
            delay(1600)
            commitAt = 0L
        }
    }

    // Live latch: the key must toggle against the current state, never a composition-time capture.
    var mutedLocal by remember(address) { mutableStateOf(volume == 0) }
    LaunchedEffect(volume) { if (volume != null) mutedLocal = volume == 0 }
    val muted = mutedLocal
    val level = levelOf(volume)
    val startLevel = state?.micDefault?.takeIf { it in 1..255 }?.let { app.ihearyou.protocol.StreamVolume.step(it) }
    val detailsLabel = stringResource(R.string.now_details_cd, sideLc)
    val readWord = when {
        commitAt != 0L -> stringResource(R.string.now_sent)
        state == null -> stringResource(R.string.now_waiting)
        state.confirmedAt <= 0L -> "—"
        System.currentTimeMillis() - state.confirmedAt < 60_000L -> stringResource(R.string.now_read_back)
        else -> stringResource(R.string.now_stale, clockOf(state.confirmedAt))
    }
    val sendVolume: (Int) -> Unit = { value ->
        onSend(Cmd.SetVolume(address, value.coerceIn(0, 255)))
        commitAt = System.currentTimeMillis()
    }
    val stepVolume: (Int) -> Unit = { steps ->
        val base = volume?.takeIf { it > 0 } ?: lastAudible
        sendVolume(app.ihearyou.protocol.StreamVolume.stepped(base, steps, state?.micDefault))
    }

    Plate(
        modifier = modifier,
        fillHeight = trackHeight == null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        // the whole header opens the aid's page, so the model gets the width a separate
        // 48 dp chevron key used to take
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onOpen() }
                .semantics(mergeDescendants = true) { contentDescription = detailsLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SideBadge(sideLetter(context, side))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    roleWord(context, info).uppercase(),
                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    modelWord(context, info, name),
                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.6.sp),
                    color = p.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Glyph(GlyphKind.Chevron, color = p.inkMuted, size = 14.dp)
        }

        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Lamp(on = state != null, tone = if (state != null) LampTone.Live else LampTone.Idle, size = 6.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                when {
                    state != null -> stringResource(R.string.link_linked)
                    link != null -> linkWord(context, link.phase)
                    name != null -> stringResource(R.string.link_connecting)
                    else -> stringResource(R.string.not_linked)
                },
                style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                color = if (state != null) p.live else p.inkMuted,
            )
            Spacer(Modifier.weight(1f))
            if (state?.streamStatus == 4) {
                Text(
                    stringResource(R.string.now_stream),
                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                    color = p.seal,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .background(p.recess)
                .border(1.dp, p.rule, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.padding(bottom = 3.dp)) {
                // while a stream level exists or something is mixed in, the microphone level is the surroundings
                val surroundings = state != null && app.ihearyou.service.StreamControl.surroundingsFor(state)
                Text(
                    stringResource(if (surroundings) R.string.now_surroundings else R.string.now_level),
                    style = InstrumentType.plate.copy(fontSize = 9.sp),
                    color = p.inkMuted,
                )
                Text(
                    readWord,
                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                    color = if (commitAt != 0L) p.seal else p.inkMuted,
                )
                // the audiologist's start level for this program, so a fitted difference between
                // the ears reads as intended rather than as a fault
                startLevel?.let { start ->
                    val cd = stringResource(R.string.now_start_level_cd, start)
                    Text(
                        stringResource(R.string.now_start_level, start),
                        style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                        color = if (start == level && !muted) p.ink else p.inkMuted,
                        modifier = Modifier.semantics { contentDescription = cd },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            LevelReading(level = level, muted = muted)
        }

        Spacer(Modifier.height(2.dp))
        LevelFader(
            value = volume,
            mark = state?.micDefault?.takeIf { it in 1..255 },
            enabled = state != null,
            trackHeight = trackHeight,
            contentDescription = stringResource(R.string.now_level_cd, sideLc),
            onCommit = sendVolume,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (trackHeight == null) Modifier.weight(1f).heightIn(min = 96.dp) else Modifier),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(
                text = "",
                onClick = { stepVolume(-1) },
                glyph = GlyphKind.Minus,
                dense = true,
                enabled = state != null,
                contentDescription = stringResource(R.string.now_quieter_cd, sideLc),
                modifier = Modifier.weight(1f),
            )
            Key(
                text = stringResource(R.string.mute),
                onClick = {
                    val mute = !mutedLocal
                    mutedLocal = mute
                    onSend(Cmd.Mute(address, on = mute))
                    commitAt = System.currentTimeMillis()
                },
                tone = if (muted) KeyTone.Primary else KeyTone.Neutral,
                dense = true,
                enabled = state != null,
                contentDescription = if (muted) {
                    stringResource(R.string.now_unmute_cd, sideLc)
                } else {
                    stringResource(R.string.now_mute_cd, sideLc)
                },
                modifier = Modifier.weight(2f),
            )
            Key(
                text = "",
                onClick = { stepVolume(1) },
                glyph = GlyphKind.Plus,
                dense = true,
                enabled = state != null,
                contentDescription = stringResource(R.string.now_louder_cd, sideLc),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // a narrow plate drops the "dBm" unit rather than wrapping it under the reading
            val roomy = maxWidth >= 172.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BatteryMeter(state?.battery, hiState = state?.hiState)
                Spacer(Modifier.weight(1f))
                SignalField(state?.rssi, unit = roomy)
            }
        }
    }
}

/** The reading rolls like a counter drum when the aid confirms a new level. */
@Composable
private fun LevelReading(level: Int?, muted: Boolean) {
    val p = LocalPanel.current
    val width = 96.dp
    if (level == null || muted) {
        Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = if (muted) stringResource(R.string.now_mute_reading) else "\u2014",
                style = InstrumentType.readingSmall.copy(fontSize = 20.sp, letterSpacing = 1.sp),
                color = p.seal,
            )
        }
        return
    }
    val reduce = !android.animation.ValueAnimator.areAnimatorsEnabled()
    AnimatedContent(
        targetState = level,
        transitionSpec = {
            if (reduce) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (targetState > initialState) {
                (slideInVertically(tween(180)) { it } + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(tween(180)) { -it } + fadeOut(tween(120)))
            } else {
                (slideInVertically(tween(180)) { -it } + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(tween(180)) { it } + fadeOut(tween(120)))
            }
        },
        label = "level",
    ) { value ->
        Text(
            text = "$value",
            style = InstrumentType.reading.copy(fontSize = 34.sp, lineHeight = 34.sp),
            color = p.ink,
            modifier = Modifier.width(width),
            textAlign = TextAlign.End,
        )
    }
}

/** A named failure with its fix, and the one key that acts on it. */
@Composable
private fun LinkProblemBand(scope: AidScope, missing: List<String>, total: Int, onOpenSetup: () -> Unit) {
    val p = LocalPanel.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val phases = missing.mapNotNull { scope.links[it]?.phase }.toSet()
    val blockedDetail = missing.firstNotNullOfOrNull { a -> scope.links[a]?.takeIf { it.phase == LinkPhase.BLOCKED }?.detail }
    data class Fix(val text: String, val key: String, val action: () -> Unit, val label: String)
    val fix = when {
        LinkPhase.BLUETOOTH_OFF in phases -> Fix(
            stringResource(R.string.now_fix_bt_off), stringResource(R.string.now_fix_bt_off_key),
            { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) },
            stringResource(R.string.open_bluetooth_settings),
        )
        LinkPhase.TAKEN_OVER in phases -> Fix(
            if (scope.rivalInstalled) stringResource(R.string.now_fix_rival_taken)
            else stringResource(R.string.now_fix_other_taken),
            stringResource(R.string.now_fix_take_back),
            { RemoteService.takeBack.tryEmit(Unit) },
            stringResource(R.string.now_fix_take_back_cd),
        )
        LinkPhase.NEEDS_PAIRING in phases -> Fix(
            stringResource(R.string.now_fix_needs_pairing), stringResource(R.string.now_fix_pair_key),
            onOpenSetup, stringResource(R.string.now_fix_pair_cd),
        )
        LinkPhase.BLOCKED in phases -> Fix(
            blockedDetail ?: stringResource(R.string.now_fix_blocked), stringResource(R.string.nav_setup),
            onOpenSetup, stringResource(R.string.now_fix_open_setup_cd),
        )
        scope.rivalInstalled -> Fix(
            stringResource(R.string.now_fix_rival), stringResource(R.string.now_fix_rival_key),
            {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", SMART3D_PACKAGE, null)),
                )
            },
            stringResource(R.string.now_fix_rival_cd),
        )
        else -> Fix(
            stringResource(R.string.now_fix_default), stringResource(R.string.now_fix_reconnect_key),
            { RemoteService.kick.tryEmit(null) }, stringResource(R.string.now_fix_take_back_cd),
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(p.warn)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(GlyphKind.Warning, color = p.onSeal, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (missing.size == total) stringResource(R.string.not_linked) else stringResource(R.string.now_n_of_m_not_linked, missing.size, total),
                style = InstrumentType.plateLarge,
                color = p.onSeal,
            )
            Text(fix.text, style = MaterialTheme.typography.bodySmall, color = p.onSeal)
        }
        Spacer(Modifier.width(8.dp))
        Key(
            text = fix.key,
            onClick = fix.action,
            tone = KeyTone.Strong,
            dense = true,
            contentDescription = fix.label,
        )
    }
}

/** Volume and mute for both ears at once: the common case, one tap away. */
@Composable
private fun PairKeys(scope: AidScope, aids: List<Pair<String, AidUiState?>>) {
    val linked = aids.mapNotNull { it.second }
    if (aids.size < 2 || linked.isEmpty()) return
    val p = LocalPanel.current
    val allMuted = linked.all { it.volume == 0 }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.now_both),
            style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            color = p.inkMuted,
            modifier = Modifier.width(44.dp),
        )
        Key(
            text = "",
            onClick = { scope.send(Cmd.VolumeAll(-1)) },
            glyph = GlyphKind.Minus,
            dense = true,
            contentDescription = stringResource(R.string.now_quieter_both_cd),
            modifier = Modifier.weight(1f),
        )
        Key(
            text = if (allMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
            onClick = { scope.send(Cmd.Mute(on = !allMuted)) },
            tone = if (allMuted) KeyTone.Primary else KeyTone.Neutral,
            dense = true,
            contentDescription = if (allMuted) stringResource(R.string.now_unmute_both_cd) else stringResource(R.string.now_mute_both_cd),
            modifier = Modifier.weight(1.5f),
        )
        Key(
            text = "",
            onClick = { scope.send(Cmd.VolumeAll(1)) },
            glyph = GlyphKind.Plus,
            dense = true,
            contentDescription = stringResource(R.string.now_louder_both_cd),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProgramsPlate(scope: AidScope, aids: List<Pair<String, AidUiState?>>, onOpenAid: (String) -> Unit) {
    val p = LocalPanel.current
    // Programs belong to the pair: a CROS transmitter has none, and a binaural pair is
    // switched together, so one row of keys drives every receiving aid.
    val receivers = aids.filter { (address, state) ->
        scope.infos[address]?.cros != true && !state?.programs.isNullOrEmpty()
    }
    val lead = receivers.firstOrNull()?.second
    val programs = lead?.programs.orEmpty()
    val split = receivers.mapNotNull { it.second?.program }.distinct().size > 1
    Plate(title = stringResource(R.string.now_program)) {
        if (programs.isEmpty()) {
            Text(
                stringResource(R.string.now_no_programs),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
            )
            return@Plate
        }
        // very large text gets two programs to a row, so a name keeps whole words
        val huge = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.45f
        val perRow = if (huge) 2 else if (programs.size <= 4) programs.size.coerceAtLeast(2) else 3
        val gap = 6.dp
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            programs.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { (index, name) ->
                        ProgramKey(
                            number = index + 1,
                            name = programLabel(androidx.compose.ui.platform.LocalContext.current, lead, index, name),
                            active = !split && lead?.program == index,
                            modifier = Modifier.weight(1f),
                        ) {
                            receivers.forEach { (address, state) ->
                                if (state?.programs?.any { it.first == index } == true) {
                                    scope.send(Cmd.SelectProgram(address, index))
                                }
                            }
                        }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        val note = when {
            split -> stringResource(R.string.now_programs_split)
            // an aid in the charger offers only its first program until it is taken out
            programs.size == 1 && aids.any { isCharging(it.second?.hiState) } -> stringResource(R.string.now_programs_charging)
            programs.size == 1 -> stringResource(R.string.now_programs_single)
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // null: closed; true: open with the name field up
        var favourites by remember { mutableStateOf<Boolean?>(null) }
        val sound = pairSound(aids, scope.infos)
        val soundHere = sound != null && !split && sound.program == lead?.program
        FavouritesStrip(scope = scope, aids = aids, onOpen = { save -> favourites = save }, saveHere = !soundHere)
        if (sound != null && soundHere) {
            var sheet by remember { mutableStateOf(false) }
            var tinnitus by remember { mutableStateOf(false) }
            SoundKeys(scope = scope, sound = sound, aids = aids, onOpen = { sheet = true }, onTinnitus = { tinnitus = true })
            if (sheet) {
                SoundSheet(
                    scope = scope, sound = sound, onDismiss = { sheet = false },
                    onSaveFavourite = {
                        sheet = false
                        favourites = true
                    },
                )
            }
            if (tinnitus && sound.tinnitus != null) TinnitusSheet(scope = scope, sound = sound, onDismiss = { tinnitus = false })
        }
        favourites?.let { save ->
            FavouritesSheet(scope = scope, aids = aids, saving = save, onDismiss = { favourites = null })
        }
    }
}

/**
 * The active program's quick presets, side by side; the engaged one carries the seal. A second
 * tap on it switches it off again.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SoundKeys(
    scope: AidScope,
    sound: app.ihearyou.service.SoundState,
    aids: List<Pair<String, AidUiState?>>,
    onOpen: () -> Unit,
    onTinnitus: () -> Unit,
) {
    val p = LocalPanel.current
    val context = LocalContext.current
    Spacer(Modifier.height(10.dp))
    val soundCd = stringResource(R.string.sound_open)
    val soundShort = stringResource(R.string.sound_open_short)
    // the full "bass · middle · treble" on a row of its own, the short word beside a preset
    val soundKey: @Composable (Modifier, Boolean) -> Unit = { m, short ->
        Key(
            text = if (short) soundShort else soundCd,
            onClick = onOpen,
            tone = KeyTone.Ghost,
            glyph = GlyphKind.Chevron,
            dense = true,
            contentDescription = soundCd,
            modifier = m,
        )
    }
    if (sound.mixIn.isNotEmpty()) {
        // while a phone or broadcast stream is mixed in, its keys take the presets' place
        MixInKeys(scope = scope, sound = sound, aids = aids)
        Spacer(Modifier.height(6.dp))
        soundKey(Modifier.fillMaxWidth(), false)
    } else {
        // two presets to a row, so a label like "Speech clarity" keeps whole words; the way into
        // bass/middle/treble joins a lone preset's row, or takes a low row of its own
        val presets = sound.presets
        val withSound = presets.size <= 1
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 2,
        ) {
            presets.forEach { kind ->
                val label = soundPresetLabel(context, kind)
                val on = sound.active == kind
                Key(
                    text = label,
                    onClick = { scope.send(Cmd.SoundPreset(sound.program, kind)) },
                    tone = if (on) KeyTone.Primary else KeyTone.Neutral,
                    // the check carries "on" as well as the colour
                    glyph = if (on) GlyphKind.Check else null,
                    enabled = !sound.busy,
                    contentDescription = if (on) stringResource(R.string.sound_preset_on_cd, label)
                    else stringResource(R.string.sound_preset_off_cd, label),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                )
            }
            if (withSound) soundKey(Modifier.weight(1f).heightIn(min = 52.dp), presets.isNotEmpty())
        }
        if (!withSound) {
            Spacer(Modifier.height(6.dp))
            soundKey(Modifier.fillMaxWidth(), false)
        }
    }
    if (sound.tinnitus != null) {
        Spacer(Modifier.height(6.dp))
        Key(
            text = stringResource(R.string.tsg_open),
            onClick = onTinnitus,
            tone = KeyTone.Ghost,
            glyph = GlyphKind.Chevron,
            dense = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (sound.failed) {
        Text(
            stringResource(R.string.sound_failed),
            style = MaterialTheme.typography.bodySmall,
            color = p.fault,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ProgramKey(
    number: Int,
    name: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val p = LocalPanel.current
    Column(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (active) p.ink else p.plateAlt)
            .border(1.dp, if (active) p.ink else p.rule, RoundedCornerShape(2.dp))
            .selectable(selected = active, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 14.dp, height = 2.dp)
                    .background(if (active) p.seal else p.ruleStrong),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "$number",
                style = InstrumentType.number.copy(fontSize = 14.sp, lineHeight = 16.sp),
                color = if (active) p.plate else p.ink,
            )
        }
        Spacer(Modifier.height(2.dp))
        FitText(
            name,
            style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.4.sp),
            color = if (active) p.plate else p.inkMuted,
            maxLines = 2,
        )
    }
}

@Composable
private fun EmptyPairPlate(onOpenSetup: () -> Unit) {
    val p = LocalPanel.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Plate(title = stringResource(R.string.now_start_here)) {
        Text(stringResource(R.string.now_no_aids_yet), style = MaterialTheme.typography.headlineSmall, color = p.ink)
        Spacer(Modifier.height(6.dp))
        HairRule()
        NumberedStep(
            1,
            stringResource(R.string.now_step1_title),
            stringResource(R.string.now_step1_body),
        )
        HairRule()
        NumberedStep(
            2,
            stringResource(R.string.now_step2_title),
            stringResource(R.string.now_step2_body),
        )
        Spacer(Modifier.height(14.dp))
        Key(
            text = stringResource(R.string.now_pair_new),
            onClick = onOpenSetup,
            tone = KeyTone.Primary,
            glyph = GlyphKind.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Key(
            text = stringResource(R.string.open_bluetooth_settings),
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                    )
                }
            },
            tone = KeyTone.Ghost,
            glyph = GlyphKind.Chevron,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

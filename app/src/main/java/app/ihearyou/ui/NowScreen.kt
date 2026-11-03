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
) {
    val fontScale = LocalDensity.current.fontScale
    val aids = orderedAids(scope.addresses, scope.sides, scope.infos, scope.states)
    val stacked = fontScale >= 1.45f

    LaunchedEffect(scope.addresses, scope.sample) {
        while (true) {
            scope.addresses.forEach { scope.send(Cmd.RefreshRssi(it)) }
            delay(4_000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 330.dp && !stacked
        val tight = stacked || maxHeight < 610.dp
        val track: Dp? = if (tight) 176.dp else null

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            NowHeader()
            if (scope.sample) SampleBand()
            LinkRuler(scope = scope, aids = aids)
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
            } else {
                if (tight) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    ) {
                        if (sideBySide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                aids.forEach { (address, state) -> ChannelFor(address, state, scope, track, onOpenAid, Modifier.weight(1f)) }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                aids.forEach { (address, state) -> ChannelFor(address, state, scope, track, onOpenAid, Modifier.fillMaxWidth()) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        PairKeys(scope = scope, aids = aids)
                        ProgramsPlate(scope = scope, aids = aids, onOpenAid = onOpenAid)
                        if (aids.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            ConditionsRuler(scope = scope, aids = aids)
                        }
                    }
                } else {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        aids.forEach { (address, state) ->
                            ChannelFor(
                                address, state, scope, null, onOpenAid,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PairKeys(scope = scope, aids = aids)
                    ProgramsPlate(scope = scope, aids = aids, onOpenAid = onOpenAid)
                    Spacer(Modifier.height(10.dp))
                    ConditionsRuler(scope = scope, aids = aids)
                }
            }
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
private fun NowHeader() {
    val p = LocalPanel.current
    Text(
        stringResource(R.string.now_wordmark),
        style = InstrumentType.wordmark,
        color = p.ink,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(
            on = linked > 0,
            tone = if (all) LampTone.Live else LampTone.Warn,
            pulse = linked > 0 && !all,
        )
        Spacer(Modifier.width(5.dp))
        Text(word, style = InstrumentType.plate, color = if (all) p.ink else p.inkMuted)
        Spacer(Modifier.weight(1f))
        if (total > 0) {
            Text(
                stringResource(R.string.now_read_at, clockOf(lastRead)),
                style = InstrumentType.plate.copy(fontSize = 9.sp),
                color = p.inkMuted,
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

    Plate(
        modifier = modifier,
        fillHeight = trackHeight == null,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SideBadge(sideLetter(context, side))
            Spacer(Modifier.width(9.dp))
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
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button) { onOpen() }
                    .semantics { contentDescription = detailsLabel },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(GlyphKind.Chevron, color = p.inkMuted, size = 16.dp)
            }
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
                Text(stringResource(R.string.now_level), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted)
                Text(
                    readWord,
                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                    color = if (commitAt != 0L) p.seal else p.inkMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            LevelReading(level = level, muted = muted)
        }

        Spacer(Modifier.height(2.dp))
        LevelFader(
            value = volume,
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
                onClick = { sendVolume((volume ?: lastAudible) - 8) },
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
                onClick = { sendVolume((volume ?: lastAudible) + 8) },
                glyph = GlyphKind.Plus,
                dense = true,
                enabled = state != null,
                contentDescription = stringResource(R.string.now_louder_cd, sideLc),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BatteryMeter(state?.battery, hiState = state?.hiState)
            Spacer(Modifier.weight(1f))
            SignalField(state?.rssi)
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
            onClick = { scope.send(Cmd.VolumeAll(-8)) },
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
            onClick = { scope.send(Cmd.VolumeAll(8)) },
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
        val perRow = if (programs.size <= 4) programs.size.coerceAtLeast(2) else 3
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
        val sound = pairSound(aids, scope.infos)
        if (sound != null && !split && sound.program == lead?.program) {
            var sheet by remember { mutableStateOf(false) }
            SoundKeys(scope = scope, sound = sound, onOpen = { sheet = true })
            if (sheet) SoundSheet(scope = scope, sound = sound, onDismiss = { sheet = false })
        }
    }
}

/**
 * The active program's quick presets, side by side; the engaged one carries the seal. A second
 * tap on it switches it off again.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SoundKeys(scope: AidScope, sound: app.ihearyou.service.SoundState, onOpen: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    Spacer(Modifier.height(10.dp))
    if (sound.presets.isNotEmpty()) {
        // two per row when there are three or the text is large, so a label never gets cut
        val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        val perRow = if (sound.presets.size > 2 || large) 2 else sound.presets.size
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = perRow,
        ) {
            sound.presets.forEach { kind ->
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
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Key(
        text = stringResource(R.string.sound_open),
        onClick = onOpen,
        tone = KeyTone.Ghost,
        glyph = GlyphKind.Chevron,
        dense = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
        Text(
            name,
            style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.4.sp),
            color = if (active) p.plate else p.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConditionsRuler(scope: AidScope, aids: List<Pair<String, AidUiState?>>) {
    val context = LocalContext.current
    val receiver = aids.firstOrNull { (address, _) -> scope.infos[address]?.cros != true } ?: aids.firstOrNull()
    val state = receiver?.second
    val linked = aids.count { it.second != null }
    val lastRead = aids.mapNotNull { it.second?.confirmedAt }.maxOrNull()
    val kind = streamKindWord(context, state?.streamType)
    val streamBase = streamWord(context, state?.streamStatus)
    val stream = if (kind != null && state?.streamStatus != 0) stringResource(R.string.join_dot, streamBase, kind) else streamBase

    Column {
        HairRule()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RulerCell(stringResource(R.string.now_stream), stream, Modifier.weight(1.35f))
            VRule(height = 26.dp)
            RulerCell(stringResource(R.string.now_link), "$linked / ${aids.size}", Modifier.weight(0.8f))
            VRule(height = 26.dp)
            RulerCell(stringResource(R.string.now_read), clockOf(lastRead), Modifier.weight(0.85f))
        }
    }
}

@Composable
private fun RulerCell(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalPanel.current
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(label, style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted)
        Text(
            value,
            style = InstrumentType.number.copy(fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
            color = p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

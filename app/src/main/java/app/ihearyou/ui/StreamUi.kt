package app.ihearyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.protocol.MixInButtons
import app.ihearyou.protocol.SoundEnhancer
import app.ihearyou.protocol.StreamVolume
import app.ihearyou.service.AidUiState
import app.ihearyou.service.Cmd
import app.ihearyou.service.SoundState
import app.ihearyou.service.StreamControl
import app.ihearyou.service.TinnitusChange
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import kotlinx.coroutines.delay

/**
 * A 13-position level with mute, for the stream and for the tinnitus sound. The level is sent on
 * release; the keys step one position. The line under it reads SENT until the aid confirms,
 * then READ BACK.
 */
@Composable
fun LevelControl(
    attenuation: Int?,
    enabled: Boolean,
    differs: Boolean,
    failed: Boolean,
    describe: (Int) -> String,
    quieterCd: String,
    louderCd: String,
    muteCd: String,
    unmuteCd: String,
    onLevel: (Int) -> Unit,
    onMute: (Boolean) -> Unit,
) {
    val p = LocalPanel.current
    var sentAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sentAt) {
        if (sentAt != 0L) {
            delay(1600)
            sentAt = 0L
        }
    }
    val muted = attenuation == 0
    val step = attenuation?.let { StreamVolume.step(it) }
    val muteWord = stringResource(R.string.now_mute_reading)
    val send: (Int) -> Unit = { s ->
        onLevel(StreamVolume.attenuationOf(s))
        sentAt = System.currentTimeMillis()
    }
    TrimSlider(
        label = stringResource(R.string.stream_level),
        value = step,
        min = 1,
        max = StreamVolume.STEPS,
        enabled = enabled,
        valueText = { if (muted && it == step) muteWord else "$it" },
        contentDescription = { if (muted && it == step) "${describe(it)}, $muteWord" else describe(it) },
        onCommit = send,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key(
            text = "",
            onClick = { send(((if (muted) 1 else step ?: 1) - 1).coerceAtLeast(1)) },
            glyph = GlyphKind.Minus,
            dense = true,
            enabled = enabled && step != null,
            contentDescription = quieterCd,
            modifier = Modifier.weight(1f),
        )
        Key(
            text = if (muted) stringResource(R.string.unmute) else stringResource(R.string.mute),
            onClick = {
                onMute(!muted)
                sentAt = System.currentTimeMillis()
            },
            tone = if (muted) KeyTone.Primary else KeyTone.Neutral,
            dense = true,
            enabled = enabled && attenuation != null,
            contentDescription = if (muted) unmuteCd else muteCd,
            modifier = Modifier.weight(2f),
        )
        Key(
            text = "",
            onClick = { send(((if (muted) 0 else step ?: 0) + 1).coerceAtMost(StreamVolume.STEPS)) },
            glyph = GlyphKind.Plus,
            dense = true,
            enabled = enabled && step != null,
            contentDescription = louderCd,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        when {
            sentAt != 0L -> stringResource(R.string.now_sent)
            attenuation == null -> stringResource(R.string.now_waiting)
            else -> stringResource(R.string.now_read_back)
        },
        style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
        color = if (sentAt != 0L) p.seal else p.inkMuted,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (differs) {
        Text(stringResource(R.string.stream_differs), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
    }
    if (failed) {
        Text(
            stringResource(R.string.sound_failed),
            style = MaterialTheme.typography.bodySmall,
            color = p.fault,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The stream level plate on the Now screen: shown while the active program is a telecoil or
 * audio-input program, or an accessory, partner microphone or broadcast stream is mixed in, and
 * hidden while an aid charges. One control sets every aid it applies to.
 */
@Composable
fun StreamPlate(scope: AidScope, aids: List<Pair<String, AidUiState?>>) {
    val linked = aids.mapNotNull { (a, st) -> st?.let { a to it } }
    if (linked.isEmpty() || linked.any { isCharging(it.second.hiState) }) return
    val targets = linked.filter { StreamControl.streamShownFor(it.second) }
        .sortedBy { if (scope.infos[it.first]?.cros == true) 1 else 0 }
    if (targets.isEmpty()) return
    val p = LocalPanel.current
    val context = LocalContext.current
    Plate(title = stringResource(R.string.stream_title), modifier = Modifier.padding(bottom = 10.dp)) {
        Text(stringResource(R.string.stream_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
        LevelControl(
            attenuation = targets.first().second.streamAttenuation,
            enabled = true,
            differs = targets.mapNotNull { it.second.streamAttenuation }.distinct().size > 1,
            failed = targets.any { it.second.streamFailed },
            describe = { context.getString(R.string.stream_level_cd, "$it") },
            quieterCd = stringResource(R.string.stream_quieter_cd),
            louderCd = stringResource(R.string.stream_louder_cd),
            muteCd = stringResource(R.string.stream_mute_cd),
            unmuteCd = stringResource(R.string.stream_unmute_cd),
            onLevel = { scope.send(Cmd.StreamLevel(it)) },
            onMute = { scope.send(Cmd.StreamMute(it)) },
        )
    }
}

/**
 * Mutually exclusive choices that wrap onto more rows instead of shrinking: each key is at least
 * 48 dp tall, the chosen one is inked and carries a check, and a screen reader hears the title,
 * the choice and its selected state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceKeys(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean,
    perRow: Int = if (options.size == 4) 2 else 3,
    onSelect: (T) -> Unit,
) {
    val p = LocalPanel.current
    val large = LocalDensity.current.fontScale > 1.3f
    val context = LocalContext.current
    val shape = RoundedCornerShape(2.dp)
    Text(
        title.uppercase(),
        style = InstrumentType.plate,
        color = p.inkMuted,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = if (large) 2 else perRow,
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            val alpha = if (enabled) 1f else 0.38f
            val cd = context.getString(R.string.se_choice_cd, title, label)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (on) p.ink else p.plate)
                    .border(1.dp, if (on) p.ink else p.ruleStrong, shape)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { if (!on) onSelect(key) }
                    .semantics { contentDescription = cd }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (on) {
                        Glyph(GlyphKind.Check, color = p.plate.copy(alpha = alpha), size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        label,
                        style = InstrumentType.plateLarge.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
                        color = (if (on) p.plate else p.ink).copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

fun enhancerTitle(context: android.content.Context, control: SoundEnhancer.Control): String = context.getString(
    when (control) {
        SoundEnhancer.Control.NOISE -> R.string.se_noise
        SoundEnhancer.Control.DNR -> R.string.se_dnr
        SoundEnhancer.Control.FOCUS -> R.string.se_focus
        SoundEnhancer.Control.WIND -> R.string.se_wind
    },
)

fun enhancerLabel(context: android.content.Context, position: SoundEnhancer.Position): String = context.getString(
    when (position) {
        SoundEnhancer.Position.NOISE_OFF, SoundEnhancer.Position.WIND_OFF -> R.string.se_off
        SoundEnhancer.Position.NOISE_MILD, SoundEnhancer.Position.WIND_MILD -> R.string.se_mild
        SoundEnhancer.Position.NOISE_MODERATE, SoundEnhancer.Position.DNR_MODERATE -> R.string.se_moderate
        SoundEnhancer.Position.NOISE_CONSIDERABLE, SoundEnhancer.Position.DNR_CONSIDERABLE -> R.string.se_considerable
        SoundEnhancer.Position.NOISE_STRONG, SoundEnhancer.Position.DNR_STRONG, SoundEnhancer.Position.WIND_STRONG -> R.string.se_strong
        SoundEnhancer.Position.NOISE_AUTO, SoundEnhancer.Position.FOCUS_AUTO -> R.string.se_auto
        SoundEnhancer.Position.FOCUS_WIDE -> R.string.se_wide
        SoundEnhancer.Position.FOCUS_MEDIUM, SoundEnhancer.Position.WIND_MEDIUM -> R.string.se_medium
        SoundEnhancer.Position.FOCUS_NARROW -> R.string.se_narrow
    },
)

/** The feature controls of a top-tier aid, under bass/middle/treble in the sound sheet. */
@Composable
fun EnhancerControls(scope: AidScope, sound: SoundState) {
    if (sound.enhancer.isEmpty()) return
    val p = LocalPanel.current
    val context = LocalContext.current
    Spacer(Modifier.height(12.dp))
    HairRule()
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.se_heading), style = MaterialTheme.typography.titleSmall, color = p.ink)
    Text(stringResource(R.string.se_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
    sound.enhancer.forEach { c ->
        ChoiceKeys(
            title = enhancerTitle(context, c.control),
            options = c.positions.map { it to enhancerLabel(context, it) },
            selected = c.selected,
            enabled = !sound.busy,
        ) { scope.send(Cmd.SoundFeature(sound.program, it)) }
    }
}

/**
 * The tinnitus sound of the active program: its level (handle 6 of that program) and, where the
 * fitting has them, noise variation, nature sounds and the two pitch edges.
 */
@Composable
fun TinnitusSheet(scope: AidScope, sound: SoundState, onDismiss: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val t = sound.tinnitus ?: return
    val members = scope.states.entries
        .filter { (_, st) -> st.program == sound.program && st.tinnitusPrograms?.contains(sound.program) == true }
        .sortedBy { if (scope.infos[it.key]?.cros == true) 1 else 0 }
        .map { it.value }
    PanelSheet(onDismiss = onDismiss) {
        HairRule()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sound_sheet_heading, sound.program + 1), style = InstrumentType.plate, color = p.inkMuted)
        Text(stringResource(R.string.tsg_heading), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Text(stringResource(R.string.tsg_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
        Spacer(Modifier.height(8.dp))
        if (members.isNotEmpty()) {
            Text(stringResource(R.string.tsg_level_title).uppercase(), style = InstrumentType.plate, color = p.inkMuted)
            LevelControl(
                attenuation = members.first().streamAttenuation,
                enabled = members.none { isCharging(it.hiState) },
                differs = members.mapNotNull { it.streamAttenuation }.distinct().size > 1,
                failed = members.any { it.streamFailed },
                describe = { context.getString(R.string.tsg_level_cd, "$it") },
                quieterCd = stringResource(R.string.tsg_quieter_cd),
                louderCd = stringResource(R.string.tsg_louder_cd),
                muteCd = stringResource(R.string.tsg_mute_cd),
                unmuteCd = stringResource(R.string.tsg_unmute_cd),
                onLevel = { scope.send(Cmd.StreamLevel(it, tinnitus = true)) },
                onMute = { scope.send(Cmd.StreamMute(it, tinnitus = true)) },
            )
        }
        val enabled = !sound.busy
        if (t.showVariation) {
            ChoiceKeys(
                title = stringResource(R.string.tsg_variation),
                options = listOf(
                    0 to stringResource(R.string.tsg_var_none),
                    1 to stringResource(R.string.tsg_var_slight),
                    2 to stringResource(R.string.tsg_var_medium),
                    3 to stringResource(R.string.tsg_var_strong),
                ),
                selected = t.variation,
                enabled = enabled,
            ) { scope.send(Cmd.TinnitusSet(sound.program, TinnitusChange.Variation(it))) }
        }
        if (t.showNature) {
            ChoiceKeys(
                title = stringResource(R.string.tsg_nature),
                options = listOf(
                    R.string.tsg_nature_rocks, R.string.tsg_nature_brook, R.string.tsg_nature_calm,
                    R.string.tsg_nature_surf, R.string.tsg_nature_waves, R.string.tsg_nature_shore,
                ).mapIndexed { i, id -> i to stringResource(id) },
                selected = t.nature,
                enabled = enabled,
                perRow = 2,
            ) { scope.send(Cmd.TinnitusSet(sound.program, TinnitusChange.Nature(it))) }
            if (t.showVariation) {
                Text(stringResource(R.string.tsg_nature_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (t.showRange) {
            val off = stringResource(R.string.tsg_edge_off)
            fun hz(v: Int) = context.getString(R.string.hz, v)
            ChoiceKeys(
                title = stringResource(R.string.tsg_low_edge),
                options = listOf(0 to off) + listOf(500, 750, 1000, 1500, 2000).mapIndexed { i, f -> i + 1 to hz(f) },
                selected = t.highPass,
                enabled = enabled,
            ) { scope.send(Cmd.TinnitusSet(sound.program, TinnitusChange.HighPass(it))) }
            ChoiceKeys(
                title = stringResource(R.string.tsg_high_edge),
                options = listOf(2000, 3000, 4000, 5000, 6000, 8000).mapIndexed { i, f -> i + 1 to hz(f) } + listOf(0 to off),
                selected = t.lowPass,
                enabled = enabled,
                perRow = 4,
            ) { scope.send(Cmd.TinnitusSet(sound.program, TinnitusChange.LowPass(it))) }
            Text(stringResource(R.string.tsg_range_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
        }
        if (sound.failed) {
            Text(stringResource(R.string.sound_failed), style = MaterialTheme.typography.bodySmall, color = p.fault, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Key(text = stringResource(R.string.dismiss), onClick = onDismiss, tone = KeyTone.Ghost, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The microphone quick keys while a phone or broadcast stream is mixed in. They take the place of
 * the program presets; the lit one matches every linked aid's level.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixInKeys(scope: AidScope, sound: SoundState, aids: List<Pair<String, AidUiState?>>) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val linked = aids.mapNotNull { it.second }.filter { it.micDefault != null && it.volume != null }
    val active = MixInButtons.active(linked.map { it.micDefault!! to it.volume!! })
    val audio = context.getSystemService(android.media.AudioManager::class.java)
    val inCall = audio?.mode.let { it == android.media.AudioManager.MODE_IN_CALL || it == android.media.AudioManager.MODE_IN_COMMUNICATION }
    val busy = linked.any { isCharging(it.hiState) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
    ) {
        sound.mixIn.forEach { kind ->
            val label = when (kind) {
                MixInButtons.Kind.FOCUS -> stringResource(if (inCall) R.string.mix_focus_call else R.string.mix_focus_stream)
                MixInButtons.Kind.HEAR_ALL -> stringResource(R.string.mix_hear_all)
            }
            val on = active == kind
            Key(
                text = label,
                onClick = { scope.send(Cmd.MixIn(kind)) },
                tone = if (on) KeyTone.Primary else KeyTone.Neutral,
                glyph = if (on) GlyphKind.Check else null,
                enabled = !busy && linked.isNotEmpty(),
                contentDescription = if (on) stringResource(R.string.sound_preset_on_cd, label) else stringResource(R.string.sound_preset_off_cd, label),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
        }
    }
    Text(
        stringResource(R.string.mix_hint),
        style = MaterialTheme.typography.bodySmall,
        color = p.inkMuted,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    if (aids.any { it.second?.streamFailed == true }) {
        Text(stringResource(R.string.sound_failed), style = MaterialTheme.typography.bodySmall, color = p.fault)
    }
}

/** A receiving aid whose receiver check reports a fault: a fault band naming the side and the fix. */
@Composable
fun ReceiverNotice(scope: AidScope, aids: List<Pair<String, AidUiState?>>, modifier: Modifier = Modifier) {
    val faulty = aids.filter { (a, st) -> st?.receiver?.error == true && scope.infos[a]?.cros != true }.map { it.first }
    if (faulty.isEmpty()) return
    val body = when {
        faulty.size >= 2 -> stringResource(R.string.rie_body_both)
        scope.sides[faulty[0]] == "Left" -> stringResource(R.string.rie_body_left)
        scope.sides[faulty[0]] == "Right" -> stringResource(R.string.rie_body_right)
        else -> stringResource(R.string.rie_body_one)
    }
    NoticeBand(title = stringResource(R.string.rie_title), body = body, modifier = modifier)
}

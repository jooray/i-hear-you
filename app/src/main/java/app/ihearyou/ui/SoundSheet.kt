package app.ihearyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ihearyou.R
import app.ihearyou.service.Cmd
import app.ihearyou.service.SoundState
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel

/**
 * Bass, middle and treble for the active program, over the Now screen. The change applies to both
 * aids, so it lives here rather than on one aid's page. Sliders send on release; the reset goes
 * back to the audiologist's values. Closed by a tap outside, Back, or the close key.
 */
@Composable
fun SoundSheet(scope: AidScope, sound: SoundState, onDismiss: () -> Unit) {
    val p = LocalPanel.current
    val dismissLabel = stringResource(R.string.dismiss)
    PanelSheet(onDismiss = onDismiss) {
        HairRule()
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.sound_sheet_heading, sound.program + 1),
            style = InstrumentType.plate,
            color = p.inkMuted,
        )
        Text(stringResource(R.string.aid_detail_enhancer), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Spacer(Modifier.height(8.dp))
        SoundTrims(scope, sound)
        Spacer(Modifier.height(8.dp))
        Key(
            text = stringResource(R.string.dismiss),
            onClick = onDismiss,
            tone = KeyTone.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SoundTrims(scope: AidScope, sound: SoundState) {
    val p = LocalPanel.current
    Text(
        stringResource(R.string.sound_hint),
        style = MaterialTheme.typography.bodySmall,
        color = p.inkMuted,
    )
    Spacer(Modifier.height(4.dp))
    val eq = sound.eq
    val bass = stringResource(R.string.sound_bass)
    val middle = stringResource(R.string.sound_middle)
    val treble = stringResource(R.string.sound_treble)
    val context = LocalContext.current
    fun signed(v: Int) = if (v > 0) "+$v" else "$v"
    val describe: (String) -> (Int) -> String = { label -> { v -> context.getString(R.string.sound_slider_cd, label, signed(v)) } }
    TrimSlider(
        label = bass, value = eq.bass, enabled = !sound.busy,
        onCommit = { scope.send(Cmd.SoundEq(sound.program, eq.copy(bass = it))) },
        contentDescription = describe(bass),
    )
    TrimSlider(
        label = middle, value = eq.middle, enabled = !sound.busy,
        onCommit = { scope.send(Cmd.SoundEq(sound.program, eq.copy(middle = it))) },
        contentDescription = describe(middle),
    )
    TrimSlider(
        label = treble, value = eq.treble, enabled = !sound.busy,
        onCommit = { scope.send(Cmd.SoundEq(sound.program, eq.copy(treble = it))) },
        contentDescription = describe(treble),
    )
    Spacer(Modifier.height(8.dp))
    Key(
        text = stringResource(R.string.sound_reset),
        onClick = { scope.send(Cmd.SoundReset(sound.program)) },
        enabled = sound.canReset && !sound.busy,
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

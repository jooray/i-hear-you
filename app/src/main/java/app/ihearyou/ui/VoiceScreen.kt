package app.ihearyou.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.button.Gesture
import app.ihearyou.domain.ButtonAction
import app.ihearyou.domain.MappingStore
import app.ihearyou.marmot.MarmotHost
import app.ihearyou.service.Cmd
import app.ihearyou.service.RemoteService
import kotlinx.coroutines.launch
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import app.ihearyou.voice.Delivery
import app.ihearyou.voice.Destination
import app.ihearyou.voice.Note
import app.ihearyou.voice.RecordingStore
import app.ihearyou.voice.ReplyMode
import app.ihearyou.voice.Transcriber
import app.ihearyou.voice.Transcription
import app.ihearyou.voice.VoiceNotes
import app.ihearyou.voice.VoiceSettings
import kotlinx.coroutines.delay

@Composable
fun VoiceScreen(scope: AidScope) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val settings = remember { VoiceSettings(context) }
    val mapping = remember { MappingStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<Pair<String, Gesture>?>(null) }
    val live by VoiceNotes.live.collectAsState()
    val notes by RecordingStore.notes.collectAsState()
    val micReady by RemoteService.micReady.collectAsState()
    val micGranted = rememberSelfPermission(Manifest.permission.RECORD_AUDIO)
    val aids = orderedAids(scope.addresses, scope.sides, scope.infos, scope.states)
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) RemoteService.start(context)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.folderUri = uri.toString()
            version++
        }
    }
    val mapped = remember(version, aids) {
        aids.any { (a, _) -> Gesture.entries.any { mapping.get(a, it) == ButtonAction.VOICE_NOTE } }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            ScreenTitle(stringResource(R.string.voice_title), stringResource(R.string.voice_subtitle))
            Text(
                stringResource(R.string.voice_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (!micGranted) {
                Spacer(Modifier.height(12.dp))
                NoticeBand(
                    title = stringResource(R.string.voice_mic_title),
                    body = stringResource(R.string.voice_mic_body),
                    tone = p.warn, ink = p.onSeal, filled = false,
                )
                Key(
                    stringResource(R.string.voice_mic_allow),
                    onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    tone = KeyTone.Primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (!micReady) {
                Spacer(Modifier.height(12.dp))
                NoticeBand(
                    title = stringResource(R.string.voice_bg_title),
                    body = stringResource(R.string.voice_bg_body),
                    tone = p.warn, ink = p.onSeal, filled = false,
                )
            }
            if (!mapped) {
                Spacer(Modifier.height(12.dp))
                NoticeBand(
                    title = stringResource(R.string.voice_unmapped_title),
                    body = stringResource(R.string.voice_unmapped_body),
                    tone = p.inkMuted, ink = p.onSeal, filled = false, glyph = GlyphKind.Keys,
                )
            }
            Spacer(Modifier.height(16.dp))

            StatusPlate(live, micGranted)
            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------------ finish presses
            Plate(title = stringResource(R.string.voice_finish_title)) {
                Text(stringResource(R.string.voice_finish_body), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
                if (aids.isEmpty()) {
                    Text(stringResource(R.string.buttons_no_aids), style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.padding(top = 8.dp))
                }
                aids.forEach { (address, _) ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SideBadge(sideLetter(context, scope.sides[address]))
                        Spacer(Modifier.width(8.dp))
                        Text(sideWord(context, scope.sides[address]), style = InstrumentType.plateLarge, color = p.ink)
                    }
                    Spacer(Modifier.height(4.dp))
                    HairRule()
                    Gesture.entries.forEachIndexed { i, g ->
                        val dest = remember(address, g, version) { settings.finish(address, g) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(role = Role.Button) { choosing = address to g }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GestureTrace(g, modifier = Modifier.width(34.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(gestureLabel(g)), style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
                            Text(stringResource(dest.labelRes), style = MaterialTheme.typography.bodySmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(4.dp))
                            Glyph(GlyphKind.Chevron, color = p.inkMuted, size = 14.dp)
                        }
                        if (i < Gesture.entries.lastIndex) HairRule()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------------ length
            Plate(title = stringResource(R.string.voice_length_title)) {
                SegmentedKeys(
                    options = VoiceSettings.LENGTHS_MIN.map { it.toString() to stringResource(R.string.voice_minutes, it) },
                    selected = remember(version) { settings.maxMinutes.toString() },
                    onSelect = { settings.maxMinutes = it.toInt(); version++ },
                )
                Text(stringResource(R.string.voice_length_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------------ transcription
            TranscriptionPlate(settings, version) { version++ }
            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------------ microphone + saving
            Plate(title = stringResource(R.string.voice_saving_title)) {
                val aidMic = remember(version) { settings.aidMic }
                ToggleKey(aidMic, stringResource(R.string.voice_aid_mic), onChange = { settings.aidMic = it; version++ })
                Text(stringResource(R.string.voice_aid_mic_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                HairRule()
                val folder = remember(version) { settings.folderUri }
                InfoRow(
                    stringResource(R.string.voice_folder),
                    folder?.let { Uri.parse(it).lastPathSegment?.substringAfter(':')?.ifBlank { null } } ?: stringResource(R.string.voice_folder_default),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Key(stringResource(R.string.voice_folder_choose), onClick = { pickFolder.launch(null) }, dense = true)
                    if (folder != null) Key(stringResource(R.string.voice_folder_reset), onClick = { settings.folderUri = null; version++ }, dense = true, tone = KeyTone.Ghost)
                }
                Text(stringResource(R.string.voice_folder_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))

            // ------------------------------------------------------------ recent
            RecentPlate(notes)
            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.voice_advanced))
            Spacer(Modifier.height(8.dp))
            // GrapheneOS makes network access a per-app toggle; an update that newly asks for it starts off
            if (!rememberSelfPermission(Manifest.permission.INTERNET)) {
                NoticeBand(
                    title = stringResource(R.string.voice_net_title),
                    body = stringResource(R.string.voice_net_body),
                    tone = p.warn, ink = p.onSeal, filled = false,
                )
                Key(
                    stringResource(R.string.voice_net_open),
                    onClick = {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    dense = true,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
            WebhookPlate(settings, version) { version++ }
            Spacer(Modifier.height(12.dp))
            WhiteNoisePlate(settings, version) { version++ }
        }

        val target = choosing
        if (target != null) {
            val options = settings.available()
            ChoiceSheet(
                heading = stringResource(R.string.join_dot, stringResource(gestureLabel(target.second)).uppercase(), sideWord(context, scope.sides[target.first])),
                title = stringResource(R.string.voice_finish_choose),
                options = options.map { Triple(it.name, stringResource(it.labelRes), stringResource(it.hintRes)) },
                selected = settings.finish(target.first, target.second).name,
                footer = if (options.size < Destination.entries.size) stringResource(R.string.voice_finish_more) else null,
                onDismiss = { choosing = null },
                onPick = { name ->
                    settings.setFinish(target.first, target.second, Destination.valueOf(name))
                    choosing = null
                    version++
                },
            )
        }
    }
}

private fun gestureLabel(g: Gesture) = when (g) {
    Gesture.SHORT -> R.string.gesture_short
    Gesture.LONG -> R.string.gesture_long
    Gesture.DOUBLE -> R.string.gesture_double
}

@Composable
private fun StatusPlate(live: VoiceNotes.Live?, micGranted: Boolean) {
    val p = LocalPanel.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) {
        while (live != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Plate(title = stringResource(R.string.voice_status_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(on = live != null, tone = if (live != null) LampTone.Fault else LampTone.Idle, pulse = live != null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (live != null) {
                    val s = ((now - live.startedAt) / 1000).coerceAtLeast(0)
                    Text(stringResource(R.string.voice_recording, s / 60, s % 60), style = InstrumentType.plateLarge, color = p.ink)
                    Text(
                        when (live.mic) {
                            app.ihearyou.voice.VoiceRecorder.Mic.HEARING_AID -> stringResource(R.string.voice_mic_aid)
                            app.ihearyou.voice.VoiceRecorder.Mic.BLUETOOTH -> stringResource(R.string.voice_mic_bluetooth, live.device ?: "?")
                            app.ihearyou.voice.VoiceRecorder.Mic.PHONE -> stringResource(R.string.voice_mic_phone)
                            null -> stringResource(R.string.voice_mic_opening)
                        },
                        style = InstrumentType.plate.copy(fontSize = 9.sp),
                        color = p.inkMuted,
                    )
                } else {
                    Text(stringResource(R.string.voice_idle), style = InstrumentType.plateLarge, color = p.ink)
                    Text(stringResource(R.string.voice_idle_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (live != null) {
            Key(stringResource(R.string.voice_stop_save), onClick = { RemoteService.commands.trySend(Cmd.VoiceStop) }, tone = KeyTone.Strong, modifier = Modifier.fillMaxWidth())
        } else {
            Key(
                stringResource(R.string.voice_record_here),
                onClick = { RemoteService.commands.trySend(Cmd.VoiceStart) },
                enabled = micGranted,
                glyph = GlyphKind.Mic,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TranscriptionPlate(settings: VoiceSettings, version: Int, changed: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val transcriber = remember { Transcriber(context) }
    val mode = remember(version) { settings.transcription }
    val provider = remember(version) { transcriber.resolve(settings.transcriber) }
    Plate(title = stringResource(R.string.voice_tx_title)) {
        SegmentedKeys(
            options = Transcription.entries.map { it.name to stringResource(it.labelRes) },
            selected = mode.name,
            onSelect = { settings.transcription = Transcription.valueOf(it); changed() },
        )
        Text(stringResource(mode.hintRes), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 6.dp))
        if (mode != Transcription.AUDIO) {
            Spacer(Modifier.height(8.dp))
            HairRule()
            val label = when {
                !transcriber.supported() -> stringResource(R.string.voice_tx_old_android)
                provider == null -> stringResource(R.string.voice_tx_none)
                else -> appLabel(context, provider.packageName)
            }
            InfoRow(stringResource(R.string.voice_tx_provider), label, modifier = Modifier.padding(top = 8.dp), tone = if (provider == null) p.fault else p.ink)
            val all = remember(version) { transcriber.providers() }
            if (all.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    all.take(4).forEach { c ->
                        Key(
                            appLabel(context, c.packageName),
                            onClick = { settings.transcriber = c.flattenToString(); changed() },
                            dense = true,
                            tone = if (c == provider) KeyTone.Primary else KeyTone.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (provider == null) {
                Text(stringResource(R.string.voice_tx_install), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // I Hear You works much better with Offline Speech to Text: point to it when it is missing
        val hasOstt = remember(version) { transcriber.providers().any { it.packageName.startsWith("app.offlinespeechtotext") } }
        if (!hasOstt) {
            Spacer(Modifier.height(8.dp))
            HairRule()
            Text(stringResource(R.string.voice_ostt_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 8.dp))
            Key(
                stringResource(R.string.voice_get_ostt),
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(OSTT_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                dense = true,
                tone = KeyTone.Primary,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

private const val OSTT_URL = "https://zapstore.dev/apps/app.offlinespeechtotext"

private fun appLabel(context: android.content.Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() + if (pkg.endsWith(".validation")) " (test)" else ""
}.getOrDefault(pkg)

@Composable
private fun RecentPlate(notes: List<Note>) {
    val p = LocalPanel.current
    val context = LocalContext.current
    Plate(title = stringResource(R.string.voice_recent_title)) {
        if (notes.isEmpty()) {
            Text(stringResource(R.string.voice_recent_empty), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
        }
        notes.take(12).forEachIndexed { i, n ->
            if (i > 0) HairRule()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(n.startedAt)),
                        style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f),
                    )
                    val s = n.durationMs / 1000
                    Text("%d:%02d".format(s / 60, s % 60), style = InstrumentType.plate, color = p.inkMuted)
                }
                val status = when (n.delivery) {
                    Delivery.RECORDING -> stringResource(R.string.voice_state_recording)
                    Delivery.PROCESSING -> stringResource(R.string.voice_state_processing)
                    Delivery.SAVED -> stringResource(R.string.voice_state_saved)
                    Delivery.SENT -> stringResource(R.string.voice_state_sent, stringResource(n.destination.labelRes))
                    Delivery.FAILED -> stringResource(R.string.voice_state_failed, stringResource(n.destination.labelRes))
                }
                Text(
                    status + if (n.transcript) " · " + stringResource(R.string.voice_state_transcript) else "",
                    style = InstrumentType.plate.copy(fontSize = 9.sp),
                    color = if (n.delivery == Delivery.FAILED) p.fault else p.inkMuted,
                )
                n.savedAs?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                (n.error ?: n.transcriptError)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.fault, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (n.delivery == Delivery.FAILED) {
                        Key(stringResource(R.string.voice_retry), onClick = { RemoteService.commands.trySend(Cmd.VoiceRetry(n.id)) }, dense = true, tone = KeyTone.Primary)
                    }
                    if (n.delivery != Delivery.RECORDING && n.delivery != Delivery.PROCESSING) {
                        Key(stringResource(R.string.voice_dest_share), onClick = { RemoteService.commands.trySend(Cmd.VoiceRetry(n.id, Destination.SHARE)) }, dense = true)
                        Key(stringResource(R.string.voice_delete), onClick = { RemoteService.commands.trySend(Cmd.VoiceDelete(n.id)) }, dense = true, tone = KeyTone.Ghost)
                    }
                }
            }
        }
        Text(stringResource(R.string.voice_recent_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun WebhookPlate(settings: VoiceSettings, version: Int, changed: () -> Unit) {
    val p = LocalPanel.current
    var url by remember(version) { mutableStateOf(settings.webhookUrl) }
    var auth by remember(version) { mutableStateOf(settings.webhookAuth) }
    Plate(title = stringResource(R.string.voice_webhook_title)) {
        Text(stringResource(R.string.voice_webhook_body), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
        Field(stringResource(R.string.voice_webhook_url), url, "https://", KeyboardType.Uri) { url = it }
        // https only: Android refuses http:// for this app, and the note would travel unencrypted
        val urlOk = url.isBlank() || VoiceSettings.isHttps(url)
        if (!urlOk) {
            Text(stringResource(R.string.voice_webhook_https), style = MaterialTheme.typography.bodySmall, color = p.fault, modifier = Modifier.padding(top = 4.dp))
        }
        Field(stringResource(R.string.voice_webhook_auth), auth, "Bearer …", KeyboardType.Ascii) { auth = it }
        Key(
            stringResource(R.string.voice_save_settings),
            onClick = { settings.webhookUrl = url; settings.webhookAuth = auth; changed() },
            dense = true,
            enabled = urlOk && (url != settings.webhookUrl || auth != settings.webhookAuth),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun WhiteNoisePlate(settings: VoiceSettings, version: Int, changed: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val phase by MarmotHost.phase.collectAsState()
    val npub by MarmotHost.npub.collectAsState()
    val error by MarmotHost.error.collectAsState()
    val groups by MarmotHost.groups.collectAsState()
    val enabled = remember(version) { settings.whiteNoise }
    val chosen = remember(version) { settings.group }
    Plate(title = stringResource(R.string.voice_wn_title)) {
        Text(stringResource(R.string.voice_wn_body), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
        Spacer(Modifier.height(8.dp))
        ToggleKey(enabled, stringResource(R.string.voice_wn_enable), onChange = {
            settings.whiteNoise = it
            changed()
            RemoteService.commands.trySend(Cmd.WhiteNoiseChanged)
        })
        if (!enabled) return@Plate
        Spacer(Modifier.height(8.dp))
        HairRule()
        InfoRow(
            stringResource(R.string.voice_wn_state),
            when (phase) {
                MarmotHost.Phase.OFF -> stringResource(R.string.voice_wn_off)
                MarmotHost.Phase.STARTING -> stringResource(R.string.voice_wn_starting)
                MarmotHost.Phase.READY -> stringResource(R.string.voice_wn_ready)
                MarmotHost.Phase.FAILED -> stringResource(R.string.voice_wn_failed, error ?: "?")
            },
            modifier = Modifier.padding(top = 8.dp),
            tone = if (phase == MarmotHost.Phase.FAILED) p.fault else p.ink,
        )
        if (phase == MarmotHost.Phase.FAILED || phase == MarmotHost.Phase.OFF) {
            Key(stringResource(R.string.voice_wn_retry), onClick = { RemoteService.commands.trySend(Cmd.WhiteNoiseChanged) }, dense = true, modifier = Modifier.padding(top = 6.dp))
        }
        npub?.let { key ->
            Text(stringResource(R.string.voice_wn_identity), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(top = 10.dp))
            Text(key, style = InstrumentType.mono, color = p.ink, modifier = Modifier.padding(top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Key(stringResource(R.string.voice_wn_copy), onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("npub", key))
                }, dense = true)
                Key(stringResource(R.string.voice_dest_share), onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), null))
                }, dense = true)
            }
            Text(stringResource(R.string.voice_wn_invite_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 6.dp))
        }
        IdentityName(settings, version, ready = phase == MarmotHost.Phase.READY, changed = changed)
        val pending = groups.filter { it.pending }
        if (pending.isNotEmpty()) {
            Text(stringResource(R.string.voice_wn_invites), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(top = 12.dp))
            pending.forEach { g ->
                // name above the keys: Key fills the width it is given, so beside the name it
                // squeezed the name to nothing
                Text(g.name.ifBlank { g.id.take(12) }, style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Key(stringResource(R.string.voice_wn_accept), onClick = {
                        RemoteService.commands.trySend(Cmd.AcceptInvite(g.id, true))
                        if (settings.group == null) { settings.group = g.id; changed() }
                    }, modifier = Modifier.weight(1f), dense = true, tone = KeyTone.Primary)
                    Spacer(Modifier.width(6.dp))
                    Key(stringResource(R.string.voice_wn_decline), onClick = { RemoteService.commands.trySend(Cmd.AcceptInvite(g.id, false)) }, modifier = Modifier.weight(1f), dense = true, tone = KeyTone.Ghost)
                }
            }
        }
        val joined = groups.filter { !it.pending && it.member }
        Text(stringResource(R.string.voice_wn_group), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(top = 12.dp))
        if (joined.isEmpty()) {
            Text(stringResource(R.string.voice_wn_no_groups), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
        }
        // the chosen group is one this identity is no longer in: notes would fail at sending
        if (phase == MarmotHost.Phase.READY && chosen != null && joined.none { it.id == chosen }) {
            Text(stringResource(R.string.voice_wn_group_gone), style = MaterialTheme.typography.bodySmall, color = p.fault, modifier = Modifier.padding(top = 4.dp))
        }
        joined.forEach { g ->
            val selected = g.id == chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { settings.group = g.id; changed() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioMark(selected)
                Spacer(Modifier.width(10.dp))
                Text(g.name.ifBlank { g.id.take(12) }, style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
        }
        Spacer(Modifier.height(10.dp))
        HairRule()
        Text(stringResource(R.string.voice_replies_title), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        val replies = remember(version) { settings.replies }
        SegmentedKeys(
            options = ReplyMode.entries.map { it.name to stringResource(it.labelRes) },
            selected = replies.name,
            onSelect = { settings.replies = ReplyMode.valueOf(it); changed() },
        )
        Text(stringResource(replies.hintRes, VoiceSettings.REPLY_WINDOW_MIN), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 6.dp))
        if (replies != ReplyMode.OFF) ReplyFilters(settings, version, changed)
        if (phase == MarmotHost.Phase.READY) {
            Spacer(Modifier.height(10.dp))
            HairRule()
            KeyBackup(settings, changed)
        }
    }
}

/**
 * The identity's display name: what group members see next to its notes. Published right away
 * when White Noise runs; otherwise kept for when the identity is made.
 */
@Composable
private fun IdentityName(settings: VoiceSettings, version: Int, ready: Boolean, changed: () -> Unit) {
    val p = LocalPanel.current
    val scope = rememberCoroutineScope()
    val published by MarmotHost.profileName.collectAsState()
    val current = published ?: remember(version) { settings.whiteNoiseName } ?: MarmotHost.DEFAULT_NAME
    var name by remember(current) { mutableStateOf(current) }
    var result by remember { mutableStateOf<String?>(null) }
    val done = stringResource(R.string.voice_wn_name_published)
    Field(stringResource(R.string.voice_wn_name), name, MarmotHost.DEFAULT_NAME, KeyboardType.Text) { name = it; result = null }
    Text(stringResource(R.string.voice_wn_name_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
    Key(
        stringResource(R.string.voice_save_settings),
        onClick = {
            val n = name.trim()
            settings.whiteNoiseName = n
            changed()
            if (ready) {
                scope.launch {
                    result = runCatching { MarmotHost.rename(n) }.fold({ done }, { MarmotHost.readable(it) })
                }
            }
        },
        dense = true,
        enabled = name.isNotBlank() && name.trim() != current,
        modifier = Modifier.padding(top = 8.dp),
    )
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.ink, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun ReplyFilters(settings: VoiceSettings, version: Int, changed: () -> Unit) {
    val p = LocalPanel.current
    val readText = remember(version) { settings.replyReadText }
    val saved = remember(version) { settings.replyIgnore.joinToString(", ") }
    var ignore by remember(version) { mutableStateOf(saved) }
    Spacer(Modifier.height(8.dp))
    ToggleKey(readText, stringResource(R.string.voice_replies_read_text), onChange = { settings.replyReadText = it; changed() })
    Text(
        stringResource(if (readText) R.string.voice_replies_read_text_on else R.string.voice_replies_read_text_off),
        style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp),
    )
    Field(stringResource(R.string.voice_replies_ignore), ignore, "\uD83C\uDF99\uFE0F", KeyboardType.Text) { ignore = it }
    Text(stringResource(R.string.voice_replies_ignore_hint), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
    Key(
        stringResource(R.string.voice_save_settings),
        onClick = { settings.replyIgnore = ignore.split(","); changed() },
        dense = true,
        enabled = ignore != saved,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Export of the identity's nsec (plain, as asked for) and import of one, replacing the identity. */
@Composable
private fun KeyBackup(settings: VoiceSettings, changed: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val imported = stringResource(R.string.voice_key_imported)
    Text(stringResource(R.string.voice_key_title), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(top = 10.dp))
    Text(stringResource(R.string.voice_key_body), style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 4.dp))
    val nsec = shown
    if (nsec == null) {
        Key(stringResource(R.string.voice_key_show), onClick = { shown = runCatching { MarmotHost.revealNsec() }.getOrElse { result = it.message; null } }, dense = true, modifier = Modifier.padding(top = 6.dp))
    } else {
        Text(nsec, style = InstrumentType.mono, color = p.ink, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Key(stringResource(R.string.voice_wn_copy), onClick = {
                val clip = ClipData.newPlainText("nsec", nsec)
                // keeps it out of the clipboard preview and keyboard suggestions
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }, dense = true)
            Key(stringResource(R.string.voice_key_hide), onClick = { shown = null }, dense = true, tone = KeyTone.Ghost)
        }
    }
    Field(stringResource(R.string.voice_key_import), input, "nsec1…", KeyboardType.Password) { input = it; confirming = false; result = null }
    if (!confirming) {
        Key(stringResource(R.string.voice_key_import_go), onClick = { confirming = true }, dense = true, enabled = input.trim().startsWith("nsec1"), modifier = Modifier.padding(top = 8.dp))
    } else {
        Text(stringResource(R.string.voice_key_import_warn), style = MaterialTheme.typography.bodySmall, color = p.fault, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Key(stringResource(R.string.voice_key_import_confirm), onClick = {
                val key = input
                confirming = false
                scope.launch {
                    result = try {
                        MarmotHost.importNsec(context, key)
                        settings.group = null
                        changed()
                        input = ""
                        shown = null
                        imported
                    } catch (t: Throwable) {
                        t.message ?: t.javaClass.simpleName
                    }
                }
            }, dense = true, tone = KeyTone.Primary)
            Key(stringResource(R.string.voice_key_cancel), onClick = { confirming = false }, dense = true, tone = KeyTone.Ghost)
        }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.ink, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val p = LocalPanel.current
    Box(
        Modifier.size(16.dp).border(1.dp, if (selected) p.seal else p.ruleStrong, RoundedCornerShape(1.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).background(p.seal))
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, type: KeyboardType, onChange: (String) -> Unit) {
    val p = LocalPanel.current
    Text(label, style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(p.recess)
            .border(1.dp, p.rule)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = InstrumentType.mono, color = p.inkMuted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = InstrumentType.mono.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Bottom sheet of radio choices, in the style of the Buttons screen's action selector. */
@Composable
private fun ChoiceSheet(
    heading: String,
    title: String,
    options: List<Triple<String, String, String>>,
    selected: String,
    footer: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val p = LocalPanel.current
    val dismissLabel = stringResource(R.string.dismiss)
    PanelSheet(onDismiss = onDismiss) {
        HairRule()
        Spacer(Modifier.height(12.dp))
        Text(heading, style = InstrumentType.plate, color = p.inkMuted)
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)).border(1.dp, p.rule)) {
            options.forEachIndexed { i, (key, label, hint) ->
                if (i > 0) HairRule()
                val isSelected = key == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(if (isSelected) p.plateAlt else p.plate)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onPick(key) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioMark(isSelected)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.ink)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
                    }
                }
            }
        }
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.inkMuted, modifier = Modifier.padding(top = 8.dp)) }
    }
}

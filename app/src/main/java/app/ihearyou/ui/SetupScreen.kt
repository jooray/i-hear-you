package app.ihearyou.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.pairing.PairState
import app.ihearyou.pairing.PairingManager
import app.ihearyou.service.RemoteService
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.service.LinkPhase
import app.ihearyou.ui.theme.LocalPanel
import app.ihearyou.ui.theme.ThemeMode
import kotlinx.coroutines.launch

private val APP_VERSION = app.ihearyou.BuildConfig.VERSION_NAME

@Composable
fun SetupScreen(scope: AidScope, themeMode: ThemeMode, onTheme: (ThemeMode) -> Unit, onOpenFinder: () -> Unit = {}) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val pairing = remember { PairingManager.get(context) }
    val hits by pairing.hits.collectAsState()
    val pairState by pairing.state.collectAsState()
    val mediaGranted = rememberNotificationAccess()
    val nearGranted = rememberSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
        rememberSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
    val logState = rememberScrollState()
    val scrollLabel = stringResource(R.string.setup_scroll_newest)

    LaunchedEffect(pairState) {
        if (pairState is PairState.Done) {
            RemoteService.stop(context)
            RemoteService.start(context)
        }
    }
    LaunchedEffect(scope.logs.size) {
        if (scope.logs.isNotEmpty()) logState.animateScrollTo(logState.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle(stringResource(R.string.setup_title), stringResource(R.string.setup_subtitle))

        if (!nearGranted) {
            Spacer(Modifier.height(16.dp))
            NoticeBand(
                title = stringResource(R.string.setup_nearby_title),
                body = stringResource(R.string.setup_nearby_body),
                tone = p.warn,
                filled = false,
            )
            Spacer(Modifier.height(8.dp))
            Key(
                text = stringResource(R.string.setup_open_settings),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
                glyph = GlyphKind.Chevron,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------------------------------------------------------- link
        Plate(title = stringResource(R.string.setup_link), modifier = Modifier.padding(top = 18.dp)) {
            val linked = scope.connected
            val total = scope.addresses.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(on = linked > 0, tone = if (linked > 0) LampTone.Live else LampTone.Idle, pulse = linked in 1 until total)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        total == 0 -> stringResource(R.string.setup_no_aids_bonded)
                        linked >= total -> stringResource(R.string.setup_all_linked)
                        linked > 0 -> stringResource(R.string.n_of_m_linked, linked, total)
                        else -> stringResource(R.string.not_linked)
                    },
                    style = InstrumentType.plate,
                    color = if (linked > 0) p.live else p.inkMuted,
                )
                if (scope.sample) {
                    Spacer(Modifier.weight(1f))
                    Stamp(stringResource(R.string.sample))
                    Spacer(Modifier.width(4.dp))
                }
            }
            Text(
                stringResource(R.string.setup_link_hint),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(
                    text = stringResource(R.string.setup_reconnect),
                    onClick = {
                        RemoteService.stop(context)
                        RemoteService.start(context)
                    },
                    glyph = GlyphKind.Refresh,
                    tone = KeyTone.Neutral,
                    modifier = Modifier.weight(1f),
                )
                Key(
                    text = stringResource(R.string.setup_stop),
                    onClick = { RemoteService.stop(context) },
                    tone = KeyTone.Ghost,
                    modifier = Modifier.weight(0.6f),
                )
            }
            if (scope.addresses.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Key(
                    text = stringResource(R.string.finder_title),
                    onClick = onOpenFinder,
                    glyph = GlyphKind.Signal,
                    tone = KeyTone.Neutral,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---------------------------------------------------------------- media
        Plate(title = stringResource(R.string.setup_media), modifier = Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(on = mediaGranted, tone = if (mediaGranted) LampTone.Live else LampTone.Warn)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (mediaGranted) stringResource(R.string.setup_access_granted) else stringResource(R.string.setup_access_missing),
                    style = InstrumentType.plate.copy(fontSize = 9.sp),
                    color = if (mediaGranted) p.live else p.warn,
                )
            }
            Text(
                stringResource(R.string.setup_media_hint),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Key(
                text = if (mediaGranted) stringResource(R.string.setup_change_access) else stringResource(R.string.setup_grant_access),
                onClick = { app.ihearyou.util.NotificationAccess.openSettings(context) },
                glyph = GlyphKind.Chevron,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------------------------------------------------------- pairing
        Plate(title = stringResource(R.string.setup_pair), modifier = Modifier.padding(top = 12.dp)) {
            Text(
                stringResource(R.string.setup_pair_hint),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(
                    text = if (pairState is PairState.Scanning) stringResource(R.string.setup_scanning) else stringResource(R.string.setup_scan),
                    onClick = { pairing.scan() },
                    glyph = GlyphKind.Scan,
                    enabled = pairState !is PairState.Progress,
                    modifier = Modifier.weight(1f),
                )
                Key(
                    text = stringResource(R.string.setup_stop),
                    onClick = { pairing.stopScan() },
                    tone = KeyTone.Ghost,
                    enabled = pairState is PairState.Scanning,
                    modifier = Modifier.weight(0.6f),
                )
            }

            when (val state = pairState) {
                is PairState.Progress -> {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Lamp(on = true, pulse = true, size = 6.dp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = p.ink)
                    }
                }
                is PairState.Failed -> {
                    Spacer(Modifier.height(10.dp))
                    NoticeBand(title = stringResource(R.string.setup_pairing_failed), body = state.message)
                }
                is PairState.Done -> {
                    Spacer(Modifier.height(10.dp))
                    NoticeBand(
                        title = stringResource(R.string.setup_bonded),
                        body = stringResource(R.string.setup_bonded_body),
                        tone = p.live,
                        ink = p.plate,
                        glyph = GlyphKind.Check,
                    )
                }
                else -> Unit
            }

            // Aids paired with Android stop advertising, so a scan cannot find them. The service
            // has already connected and knows which ones lack a key: offer them directly.
            val waiting = scope.addresses.filter { scope.links[it]?.phase == LinkPhase.NEEDS_PAIRING }
            if (waiting.isNotEmpty() && pairState !is PairState.Progress) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.setup_ready_to_pair),
                    style = InstrumentType.plate,
                    color = p.inkMuted,
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(2.dp))
                        .border(1.dp, p.rule),
                ) {
                    waiting.forEachIndexed { index, address ->
                        if (index > 0) HairRule()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .background(p.plateAlt)
                                .clickable(role = Role.Button) { pairing.pair(address) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    listOfNotNull(scope.sides[address], scope.names[address]?.trim())
                                        .joinToString(" · ")
                                        .ifEmpty { stringResource(R.string.setup_unnamed_aid) },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = p.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    address,
                                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                                    color = p.inkMuted,
                                )
                            }
                            Text(
                                stringResource(R.string.setup_pair_this),
                                style = MaterialTheme.typography.titleSmall,
                                color = p.live,
                            )
                        }
                    }
                }
            }

            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(2.dp))
                        .border(1.dp, p.rule),
                ) {
                    hits.forEachIndexed { index, hit ->
                        if (index > 0) HairRule()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .background(p.plateAlt)
                                .clickable(role = Role.Button) { pairing.pair(hit.address) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    hit.name ?: stringResource(R.string.setup_unnamed_aid),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = p.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    hit.address,
                                    style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                                    color = p.inkMuted,
                                )
                            }
                            SignalField(hit.rssi)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- appearance
        Plate(title = stringResource(R.string.setup_appearance), modifier = Modifier.padding(top = 12.dp)) {
            SegmentedKeys(
                options = listOf(
                    ThemeMode.SYSTEM.name to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT.name to stringResource(R.string.theme_light),
                    ThemeMode.DARK.name to stringResource(R.string.theme_dark),
                ),
                selected = themeMode.name,
                onSelect = { onTheme(ThemeMode.valueOf(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------------------------------------------------------- language
        Plate(title = stringResource(R.string.setup_language), modifier = Modifier.padding(top = 12.dp)) {
            val activity = context as? android.app.Activity
            var language by remember { mutableStateOf(app.ihearyou.domain.Language.current(context)) }
            val systemLabel = stringResource(R.string.theme_system)
            val names = app.ihearyou.domain.Language.choices.map { (tag, native) -> tag to (native ?: systemLabel) }
            names.chunked(3).forEachIndexed { row, options ->
                if (row > 0) Spacer(Modifier.height(6.dp))
                SegmentedKeys(
                    options = options,
                    selected = language,
                    onSelect = { tag ->
                        if (tag != language) {
                            language = tag
                            app.ihearyou.domain.Language.set(context, tag)
                            // Android 13+ recreates the activity itself; 12 needs a nudge
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) activity?.recreate()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---------------------------------------------------------------- sample
        Plate(title = stringResource(R.string.setup_sample_pair), modifier = Modifier.padding(top = 12.dp)) {
            ToggleKey(
                checked = scope.sample,
                label = stringResource(R.string.setup_sample_preview),
                onChange = scope.setSample,
            )
            Text(
                stringResource(R.string.setup_sample_hint),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
            )
        }

        // ---------------------------------------------------------------- log
        Plate(title = stringResource(R.string.setup_log), modifier = Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.setup_log_lines, scope.logs.size, scope.logs.size),
                    style = InstrumentType.plate.copy(fontSize = 9.sp),
                    color = p.inkMuted,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button) { coroutine.launch { logState.animateScrollTo(logState.maxValue) } }
                        .semantics { contentDescription = scrollLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(GlyphKind.Refresh, color = p.inkMuted, size = 16.dp)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.recess)
                    .border(1.dp, p.rule)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column(Modifier.verticalScroll(logState)) {
                    if (scope.logs.isEmpty()) {
                        Text(stringResource(R.string.setup_log_empty), style = InstrumentType.mono, color = p.inkMuted)
                    }
                    scope.logs.forEach { line ->
                        Text(line, style = InstrumentType.mono, color = p.inkMuted)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- about
        Plate(title = stringResource(R.string.setup_about), modifier = Modifier.padding(top = 12.dp)) {
            InfoRow(stringResource(R.string.setup_version), stringResource(R.string.setup_version_value, APP_VERSION))
        }
    }
}

@Composable
fun rememberSelfPermission(permission: String): Boolean {
    val context = LocalContext.current
    fun current() = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(current()) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner, permission) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

@Composable
fun rememberNotificationAccess(): Boolean {
    val context = LocalContext.current
    var granted by remember { androidx.compose.runtime.mutableStateOf(hasNotificationAccess(context)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = hasNotificationAccess(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}

fun hasNotificationAccess(context: Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return@runCatching false
    enabled.split(":").any {
        ComponentName.unflattenFromString(it)?.packageName == context.packageName
    }
}.getOrDefault(false)

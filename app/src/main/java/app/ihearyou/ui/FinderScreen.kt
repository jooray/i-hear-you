package app.ihearyou.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.domain.LastSeenStore
import app.ihearyou.domain.Proximity
import app.ihearyou.service.AidUiState
import app.ihearyou.service.Cmd
import app.ihearyou.service.RemoteService
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Finding a mislaid aid: one proximity bar per side from the phone's signal reading, read more
 * often while this screen is open, and an optional tick from the phone's own speaker that gets
 * quicker as the signal grows. An aid out of range shows when it was last connected.
 */
@Composable
fun FinderScreen(scope: AidScope, onBack: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val aids = orderedAids(scope.addresses, scope.sides, scope.infos, scope.states)
    val linked = aids.filter { it.second != null }.map { it.first }

    // faster signal reads only while this screen is shown; the Now screen goes back to its own pace
    val latestLinked by rememberUpdatedState(linked)
    LaunchedEffect(scope.sample) {
        if (!scope.sample) RemoteService.kickReconnect()
        while (true) {
            latestLinked.forEach { scope.send(Cmd.RefreshRssi(it)) }
            delay(Proximity.FINDER_POLL_MS)
        }
    }

    val store = remember { LastSeenStore(context) }
    val seenVersion by LastSeenStore.changed.collectAsState()

    var tickOn by remember { mutableStateOf(false) }
    var tickFor by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle(stringResource(R.string.finder_title), stringResource(R.string.finder_subtitle), onBack = onBack)
        if (scope.sample) {
            Spacer(Modifier.height(10.dp))
            Stamp(stringResource(R.string.sample))
        }
        Text(
            stringResource(R.string.finder_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = p.ink,
            modifier = Modifier.padding(top = 12.dp),
        )

        Plate(title = stringResource(R.string.finder_signal_plate), modifier = Modifier.padding(top = 16.dp)) {
            if (aids.isEmpty()) {
                Text(stringResource(R.string.now_no_aids), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
            }
            aids.forEachIndexed { i, (address, state) ->
                if (i > 0) {
                    Spacer(Modifier.height(12.dp))
                    HairRule()
                    Spacer(Modifier.height(12.dp))
                }
                val lastSeen = remember(seenVersion, address) { store.get(address) }
                AidProximity(context, scope.sides[address], state, lastSeen)
            }
        }

        Plate(title = stringResource(R.string.finder_tick_title), modifier = Modifier.padding(top = 12.dp)) {
            ToggleKey(
                checked = tickOn,
                label = stringResource(R.string.finder_tick),
                onChange = { tickOn = it },
            )
            Text(
                stringResource(R.string.finder_tick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
            )
            if (tickOn && linked.size > 1) {
                Text(
                    stringResource(R.string.finder_tick_for),
                    style = InstrumentType.plate.copy(fontSize = 9.sp),
                    color = p.inkMuted,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                val weakest = linked.minByOrNull { scope.states[it]?.rssi ?: 0 }
                SegmentedKeys(
                    options = linked.map { it to sideTitle(context, scope.sides[it]) },
                    selected = tickFor?.takeIf { it in linked } ?: weakest.orEmpty(),
                    onSelect = { tickFor = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text(
            stringResource(R.string.finder_local),
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    if (tickOn) {
        val target = tickFor?.takeIf { it in linked } ?: linked.minByOrNull { scope.states[it]?.rssi ?: 0 }
        val rssi = target?.let { scope.states[it]?.rssi }
        ProximityTick(rssi)
    }
}

@Composable
private fun AidProximity(context: Context, side: String?, state: AidUiState?, lastSeen: Long?) {
    val p = LocalPanel.current
    val title = sideTitle(context, side)
    val rssi = state?.rssi
    val percent = rssi?.let(Proximity::percent)
    val word = when {
        state == null -> stringResource(R.string.finder_out_of_range)
        percent == null -> stringResource(R.string.finder_waiting)
        else -> bandWord(context, Proximity.band(percent))
    }
    val meterLabel = if (percent != null) {
        stringResource(R.string.finder_meter_cd, title, word, percent)
    } else {
        "$title: $word"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SideBadge(sideLetter(context, side), tone = if (state != null) p.ink else p.inkMuted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = InstrumentType.plate, color = p.inkMuted)
            Text(
                word,
                style = InstrumentType.readingSmall.copy(fontSize = 28.sp, lineHeight = 30.sp),
                color = if (state != null) p.ink else p.inkMuted,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    ProximityBar(
        percent = percent,
        modifier = Modifier.semantics {
            contentDescription = meterLabel
            progressBarRangeInfo = ProgressBarRangeInfo((percent ?: 0) / 100f, 0f..1f)
        },
    )
    Spacer(Modifier.height(4.dp))
    if (state != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.finder_far),
                style = InstrumentType.plate.copy(fontSize = 9.sp),
                color = p.inkMuted,
            )
            Text(
                if (rssi != null && percent != null) stringResource(R.string.finder_dbm, rssi, percent) else "—",
                style = InstrumentType.mono,
                color = p.inkMuted,
            )
            Text(
                stringResource(R.string.finder_very_close),
                style = InstrumentType.plate.copy(fontSize = 9.sp),
                color = p.inkMuted,
            )
        }
    } else {
        Text(
            lastSeenText(context, lastSeen),
            style = MaterialTheme.typography.bodyMedium,
            color = p.ink,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            stringResource(R.string.finder_out_hint),
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** One continuous bar in a recessed window, with the two word boundaries marked on the scale. */
@Composable
private fun ProximityBar(percent: Int?, modifier: Modifier = Modifier) {
    val p = LocalPanel.current
    val shape = RoundedCornerShape(2.dp)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(p.recess)
            .border(1.dp, p.rule, shape),
    ) {
        val inset = 4.dp.toPx()
        val w = size.width - inset * 2
        val h = size.height - inset * 2
        if (percent != null) {
            drawRect(p.ink, topLeft = Offset(inset, inset), size = Size(w * percent / 100f, h))
        }
        // printed scale: 20 minor ticks; the two word boundaries run the full height
        for (i in 0..20) {
            val x = inset + w * i / 20f
            val tall = i % 5 == 0
            drawRect(
                p.ruleStrong,
                topLeft = Offset(x - 0.5f, size.height - inset - if (tall) 10.dp.toPx() else 5.dp.toPx()),
                size = Size(1f, if (tall) 10.dp.toPx() else 5.dp.toPx()),
            )
        }
        for (mark in listOf(Proximity.CLOSER_PERCENT, Proximity.CLOSE_PERCENT)) {
            val x = inset + w * mark / 100f
            drawRect(p.ruleStrong, topLeft = Offset(x - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}

private fun bandWord(context: Context, band: Proximity.Band): String = context.getString(
    when (band) {
        Proximity.Band.FAR -> R.string.finder_far
        Proximity.Band.CLOSER -> R.string.finder_closer
        Proximity.Band.VERY_CLOSE -> R.string.finder_very_close
    },
)

private fun lastSeenText(context: Context, at: Long?): String {
    if (at == null) return context.getString(R.string.finder_never_seen)
    return when (val ago = Proximity.ago(System.currentTimeMillis(), at)) {
        Proximity.Ago.JustNow -> context.getString(R.string.finder_last_seen_now)
        is Proximity.Ago.Minutes -> context.getString(R.string.finder_last_seen_minutes, ago.n, clockOf(at))
        is Proximity.Ago.Hours -> context.getString(R.string.finder_last_seen_hours, ago.n, clockOf(at))
        is Proximity.Ago.Days -> {
            val locale = context.resources.configuration.locales[0]
            val date = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(at))
            context.resources.getQuantityString(R.plurals.finder_last_seen_days, ago.n, ago.n, "$date ${clockOf(at)}")
        }
    }
}

/** Ticks from the phone's speaker at a pace set by [rssi]; silent while there is no reading. */
@Composable
private fun ProximityTick(rssi: Int?) {
    val context = LocalContext.current
    val click = remember { ProximityClick(context) }
    DisposableEffect(click) { onDispose { click.release() } }
    val latest by rememberUpdatedState(rssi)
    LaunchedEffect(click) {
        while (true) {
            val r = latest
            if (r == null) {
                delay(500)
                continue
            }
            withContext(Dispatchers.Default) { click.tick() }
            delay(Proximity.tickIntervalMs(Proximity.percent(r)))
        }
    }
}

/**
 * A short synthesised click, routed to the phone's built-in speaker so it never plays in the
 * aids being looked for.
 */
private class ProximityClick(context: Context) {
    private val rate = 24_000
    private val track: AudioTrack? = runCatching {
        val n = rate * 35 / 1000
        val pcm = ShortArray(n) { k ->
            val env = 1.0 - k.toDouble() / n
            (sin(2 * PI * 1_500 * k / rate) * env * 9_000).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { t ->
                t.write(pcm, 0, pcm.size)
                val speaker = context.getSystemService(AudioManager::class.java)
                    ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) t.setPreferredDevice(speaker)
            }
    }.getOrNull()

    fun tick() {
        val t = track ?: return
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
    }
}

package app.ihearyou.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import app.ihearyou.ui.theme.ThemeMode

private enum class Dest(@StringRes val labelRes: Int, val glyph: GlyphKind) {
    Now(R.string.nav_now, GlyphKind.Now),
    Buttons(R.string.nav_buttons, GlyphKind.Keys),
    Voice(R.string.nav_voice, GlyphKind.Mic),
    Setup(R.string.nav_setup, GlyphKind.Setup),
}

private val destinations = Dest.entries

@Composable
fun IHearYouApp(themeMode: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val p = LocalPanel.current
    val scope = rememberAidScope()
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var detailAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var finderOpen by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = detailAddress != null) { detailAddress = null }
    BackHandler(enabled = finderOpen && detailAddress == null) { finderOpen = false }

    fun open(address: String) {
        detailAddress = address
    }

    fun goTo(index: Int) {
        detailAddress = null
        finderOpen = false
        destination = index
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(p.ground)) {
        val expanded = maxWidth >= 600.dp
        if (expanded) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                PanelRail(
                    selected = destination,
                    onSelect = ::goTo,
                )
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                        Host(scope, destination, detailAddress, finderOpen, { finderOpen = it }, ::open, ::goTo, { detailAddress = null }, themeMode, onTheme)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    Host(scope, destination, detailAddress, finderOpen, { finderOpen = it }, ::open, ::goTo, { detailAddress = null }, themeMode, onTheme)
                }
                PanelSwitchPlate(
                    selected = destination,
                    onSelect = ::goTo,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun Host(
    scope: AidScope,
    destination: Int,
    detailAddress: String?,
    finderOpen: Boolean,
    onFinder: (Boolean) -> Unit,
    onOpenAid: (String) -> Unit,
    onGoTo: (Int) -> Unit,
    onCloseDetail: () -> Unit,
    themeMode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
) {
    if (detailAddress != null) {
        AidDetailScreen(
            scope = scope,
            address = detailAddress,
            onBack = onCloseDetail,
        )
        return
    }
    if (finderOpen) {
        FinderScreen(scope = scope, onBack = { onFinder(false) })
        return
    }
    when (destinations[destination]) {
        Dest.Now -> NowScreen(
            scope = scope, onOpenAid = onOpenAid, onOpenSetup = { onGoTo(Dest.Setup.ordinal) },
            onOpenFinder = { onFinder(true) },
        )
        Dest.Buttons -> ButtonsScreen(scope = scope)
        Dest.Voice -> VoiceScreen(scope = scope)
        Dest.Setup -> SetupScreen(scope = scope, themeMode = themeMode, onTheme = onTheme, onOpenFinder = { onFinder(true) })
    }
}

/** Bottom switch plate: the destinations, one shared rule above, an inked tab when selected. */
@Composable
private fun PanelSwitchPlate(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPanel.current
    Column(modifier.fillMaxWidth()) {
        HairRule(color = p.ruleStrong)
        Row(Modifier.fillMaxWidth().height(62.dp).background(p.plate)) {
            destinations.forEachIndexed { index, dest ->
                val isSelected = index == selected
                val label = stringResource(dest.labelRes)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .semantics { contentDescription = label }
                        .padding(top = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(30.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (isSelected) p.seal else p.plate),
                    )
                    Spacer(Modifier.height(7.dp))
                    Glyph(dest.glyph, color = if (isSelected) p.ink else p.inkMuted, size = 19.dp)
                    Spacer(Modifier.height(3.dp))
                    // large text shrinks to fit the tab instead of losing its last letters
                    FitText(
                        label.uppercase(),
                        style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 1.1.sp),
                        color = if (isSelected) p.ink else p.inkMuted,
                        maxLines = 1,
                        minScale = 0.6f,
                    )
                }
            }
        }
    }
}

/** Navigation rail on expanded width: the same three plates, stacked. */
@Composable
private fun PanelRail(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPanel.current
    Row {
        Column(
            Modifier
                .width(88.dp)
                .fillMaxHeight()
                .background(p.plate)
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.nav_wordmark),
                style = InstrumentType.plate.copy(fontSize = 10.sp),
                color = p.inkMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            destinations.forEachIndexed { index, dest ->
                val isSelected = index == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(if (isSelected) p.plateAlt else p.plate)
                        .clickable(role = Role.Tab) { onSelect(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(if (isSelected) p.seal else p.plate),
                    )
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Glyph(dest.glyph, color = if (isSelected) p.ink else p.inkMuted, size = 20.dp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(dest.labelRes).uppercase(),
                            style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.9.sp),
                            color = if (isSelected) p.ink else p.inkMuted,
                        )
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(p.ruleStrong))
    }
}

/** Shared screen title used by every secondary surface. */
@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPanel.current
    val backLabel = stringResource(R.string.back)
    Column {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button) { onBack() }
                        .semantics { contentDescription = backLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(GlyphKind.Back, color = p.ink, size = 20.dp)
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = p.ink)
                if (subtitle != null) {
                    Text(
                        subtitle.uppercase(),
                        style = InstrumentType.plate.copy(fontSize = 9.sp),
                        color = p.inkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing?.invoke()
        }
        HairRule()
    }
}

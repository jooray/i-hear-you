package app.ihearyou.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import app.ihearyou.R
import app.ihearyou.button.Gesture
import app.ihearyou.domain.ButtonAction
import app.ihearyou.domain.MappingStore
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel

@StringRes
private fun gestureName(gesture: Gesture) = when (gesture) {
    Gesture.SHORT -> R.string.gesture_short
    Gesture.LONG -> R.string.gesture_long
    Gesture.DOUBLE -> R.string.gesture_double
}

@StringRes
private fun gestureHint(gesture: Gesture) = when (gesture) {
    Gesture.SHORT -> R.string.gesture_short_hint
    Gesture.LONG -> R.string.gesture_long_hint
    Gesture.DOUBLE -> R.string.gesture_double_hint
}

@Composable
fun ButtonsScreen(scope: AidScope) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val store = remember { MappingStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf<Pair<String, Gesture>?>(null) }
    val aids = orderedAids(scope.addresses, scope.sides, scope.infos, scope.states)
    val mediaGranted = rememberNotificationAccess()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            ScreenTitle(stringResource(R.string.buttons_title), stringResource(R.string.buttons_subtitle))
            Text(
                stringResource(R.string.buttons_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (!mediaGranted) {
                Spacer(Modifier.height(12.dp))
                NoticeBand(
                    title = stringResource(R.string.buttons_media_title),
                    body = stringResource(R.string.buttons_media_body),
                    tone = p.warn,
                    ink = p.onSeal,
                    filled = false,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (aids.isEmpty()) {
                Plate(title = stringResource(R.string.buttons_mapping)) {
                    Text(stringResource(R.string.buttons_no_aids), style = MaterialTheme.typography.titleMedium, color = p.ink)
                    Text(
                        stringResource(R.string.buttons_no_aids_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.inkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            aids.forEachIndexed { index, (address, state) ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                MappingPlate(
                    address = address,
                    side = scope.sides[address],
                    model = modelWord(context, scope.infos[address], scope.names[address]),
                    linked = state != null,
                    store = store,
                    version = version,
                    onSelect = { gesture -> selecting = address to gesture },
                )
            }

        }

        val target = selecting
        if (target != null) {
            ActionSelector(
                address = target.first,
                gesture = target.second,
                side = scope.sides[target.first],
                current = store.get(target.first, target.second),
                onDismiss = { selecting = null },
                onPick = { action ->
                    store.set(target.first, target.second, action)
                    selecting = null
                    version++
                },
            )
        }
    }
}

@Composable
private fun MappingPlate(
    address: String,
    side: String?,
    model: String,
    linked: Boolean,
    store: MappingStore,
    version: Int,
    onSelect: (Gesture) -> Unit,
) {
    val p = LocalPanel.current
    val context = LocalContext.current
    Plate(title = stringResource(R.string.aid_with_side, sideWord(context, side))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SideBadge(sideLetter(context, side))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(model, style = InstrumentType.plateLarge, color = p.ink)
                Text(shortAddress(address), style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.8.sp), color = p.inkMuted)
            }
            Lamp(on = linked, tone = if (linked) LampTone.Live else LampTone.Idle, size = 6.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                if (linked) stringResource(R.string.link_linked) else stringResource(R.string.not_linked),
                style = InstrumentType.plate.copy(fontSize = 9.sp),
                color = if (linked) p.live else p.inkMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        HairRule()
        Gesture.entries.forEachIndexed { index, gesture ->
            val action = remember(address, gesture, version) { store.get(address, gesture) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .clickable(role = Role.Button) { onSelect(gesture) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GestureTrace(gesture, modifier = Modifier.width(34.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(gestureName(gesture)), style = MaterialTheme.typography.titleSmall, color = p.ink)
                    Text(
                        stringResource(gestureHint(gesture)),
                        style = InstrumentType.plate.copy(fontSize = 9.sp, letterSpacing = 0.9.sp),
                        color = p.inkMuted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(action.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Glyph(GlyphKind.Chevron, color = p.inkMuted, size = 14.dp)
            }
            if (index < Gesture.entries.lastIndex) HairRule()
        }
    }
}

@Composable
private fun ActionSelector(
    address: String,
    gesture: Gesture,
    side: String?,
    current: ButtonAction,
    onDismiss: () -> Unit,
    onPick: (ButtonAction) -> Unit,
) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val dismissLabel = stringResource(R.string.dismiss)
    val closeLabel = stringResource(R.string.close)
    PanelSheet(onDismiss = onDismiss) {
        HairRule()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.join_dot, stringResource(gestureName(gesture)).uppercase(), sideWord(context, side)),
                    style = InstrumentType.plate,
                    color = p.inkMuted,
                )
                Text(stringResource(R.string.buttons_choose), style = MaterialTheme.typography.titleMedium, color = p.ink)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button) { onDismiss() }
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(GlyphKind.Close, color = p.ink, size = 18.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, p.rule),
        ) {
            ButtonAction.entries.forEachIndexed { index, action ->
                if (index > 0) HairRule()
                val selected = action == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(if (selected) p.plateAlt else p.plate)
                        .selectable(selected = selected, role = Role.RadioButton) { onPick(action) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .border(1.dp, if (selected) p.seal else p.ruleStrong, RoundedCornerShape(1.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(Modifier.size(8.dp).background(p.seal))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(action.labelRes), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                        Text(stringResource(action.hintRes), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
                    }
                    if (selected) {
                        Text(stringResource(R.string.buttons_set), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.seal)
                    }
                }
            }
        }
    }
}

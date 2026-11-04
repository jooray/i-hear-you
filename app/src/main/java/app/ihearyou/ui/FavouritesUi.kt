package app.ihearyou.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.domain.Favourite
import app.ihearyou.domain.FavouritePart
import app.ihearyou.domain.FavouriteRun
import app.ihearyou.domain.Favourites
import app.ihearyou.domain.PairSnapshot
import app.ihearyou.protocol.Eq
import app.ihearyou.service.AidUiState
import app.ihearyou.service.Cmd
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel
import kotlinx.coroutines.delay

/** How many favourites the Now screen shows before "All…". */
private const val VISIBLE_FAVOURITES = 3

/** The pair's current values in a favourite's terms; sound parts only for the program shown. */
fun pairSnapshot(scope: AidScope, aids: List<Pair<String, AidUiState?>>): PairSnapshot {
    val receivers = aids.filter { (a, st) -> scope.infos[a]?.cros != true && !st?.programs.isNullOrEmpty() }
    val programs = receivers.mapNotNull { it.second?.program }.distinct()
    val program = programs.singleOrNull()
    val volumes = aids.mapNotNull { (a, st) -> st?.volume?.let { scope.aidKey(a) to it } }.toMap()
    val sound = pairSound(aids, scope.infos)?.takeIf { program != null && it.program == program }
    return PairSnapshot(
        program = program,
        volumes = volumes,
        eq = sound?.eq,
        preset = sound?.active,
        enhancer = sound?.enhancer.orEmpty().mapNotNull { c -> c.selected?.takeIf { it in c.positions } },
    )
}

private fun defaultName(context: Context, scope: AidScope, aids: List<Pair<String, AidUiState?>>, program: Int?): String {
    val lead = aids.firstOrNull { (a, st) -> scope.infos[a]?.cros != true && !st?.programs.isNullOrEmpty() }?.second
    val base = if (program == null) {
        context.getString(R.string.fav_title)
    } else {
        val aidName = lead?.programs?.firstOrNull { it.first == program }?.second
            ?: context.getString(R.string.program_default_name, program + 1)
        programLabel(context, lead, program, aidName)
    }
    return "$base · ${clockOf(System.currentTimeMillis())}"
}

/** "P2 Restaurant · L 62 · R 58 · Speech clarity", for a favourite's row in the sheet. */
private fun summary(context: Context, f: Favourite, scope: AidScope, aids: List<Pair<String, AidUiState?>>): String {
    val parts = ArrayList<String>()
    if (f.program != null) {
        val lead = aids.firstOrNull { (a, st) -> scope.infos[a]?.cros != true && !st?.programs.isNullOrEmpty() }?.second
        val aidName = lead?.programs?.firstOrNull { it.first == f.program }?.second
        parts += if (aidName != null) "P${f.program + 1} ${programLabel(context, lead, f.program, aidName)}" else "P${f.program + 1}"
    }
    aids.forEach { (a, _) ->
        f.volumes[scope.aidKey(a)]?.let { parts += "${sideLetter(context, scope.sides[a])} ${levelOf(it)}" }
    }
    if (f.preset != null) {
        parts += soundPresetLabel(context, f.preset)
    } else if (f.eq != null && f.eq != Eq.FLAT) {
        fun signed(v: Int) = if (v > 0) "+$v" else "$v"
        parts += "${context.getString(R.string.sound_bass)} ${signed(f.eq.bass)} " +
            "${context.getString(R.string.sound_middle)} ${signed(f.eq.middle)} " +
            "${context.getString(R.string.sound_treble)} ${signed(f.eq.treble)}"
    }
    f.enhancer.forEach { parts += enhancerLabel(context, it) }
    return parts.joinToString(" · ")
}

private fun partWord(context: Context, part: FavouritePart): String = context.getString(
    when (part) {
        FavouritePart.PROGRAM -> R.string.fav_part_program
        FavouritePart.VOLUME -> R.string.fav_part_volume
        FavouritePart.SOUND -> R.string.fav_part_sound
        FavouritePart.ENHANCER -> R.string.fav_part_enhancer
    },
)

/**
 * The Now screen's row of favourites under the program keys: a tap applies one, the one that
 * matches what the aids report carries a check, and "All…" opens the sheet to save, rename and delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavouritesStrip(
    scope: AidScope,
    aids: List<Pair<String, AidUiState?>>,
    onOpen: (save: Boolean) -> Unit,
    /** False when the sound panel offers saving, so an empty strip takes no room. */
    saveHere: Boolean = true,
) {
    val p = LocalPanel.current
    val context = LocalContext.current
    if (aids.none { it.second != null }) return
    // saving lives in the sound panel; the strip appears once there is something to recall
    if (scope.favourites.isEmpty() && !saveHere) {
        FavouriteRunLine(scope.favouriteRun)
        return
    }
    val snapshot = pairSnapshot(scope, aids)
    val run = scope.favouriteRun
    val busy = run?.running == true
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.fav_title).uppercase(),
        style = InstrumentType.plate.copy(fontSize = 9.sp),
        color = p.inkMuted,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    if (scope.favourites.isEmpty()) {
        Key(
            text = stringResource(R.string.fav_save),
            onClick = { onOpen(true) },
            tone = KeyTone.Ghost,
            glyph = GlyphKind.Plus,
            dense = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        val large = LocalDensity.current.fontScale > 1.3f
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = if (large) 1 else 2,
        ) {
            scope.favourites.take(VISIBLE_FAVOURITES).forEach { f ->
                val matched = Favourites.matches(f, snapshot)
                Key(
                    text = f.name,
                    onClick = { scope.send(Cmd.ApplyFavourite(f)) },
                    tone = if (matched) KeyTone.Strong else KeyTone.Neutral,
                    // the check says "matches" as well as the ink face
                    glyph = if (matched) GlyphKind.Check else null,
                    enabled = !busy,
                    contentDescription = if (matched) stringResource(R.string.fav_matched_cd, f.name)
                    else stringResource(R.string.fav_apply_cd, f.name),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                )
            }
            Key(
                text = stringResource(R.string.fav_all),
                onClick = { onOpen(false) },
                tone = KeyTone.Ghost,
                glyph = GlyphKind.Chevron,
                contentDescription = stringResource(R.string.fav_all_cd),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
        }
    }
    FavouriteRunLine(run)
}

/** Progress while a favourite is applied, then what was set; a failure stays until dismissed. */
@Composable
fun FavouriteRunLine(run: FavouriteRun?) {
    val p = LocalPanel.current
    val context = LocalContext.current
    var hiddenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(run?.finishedAt) {
        val r = run ?: return@LaunchedEffect
        if (!r.running && r.failed.isEmpty() && r.finishedAt > 0L) {
            delay(8_000)
            hiddenAt = r.finishedAt
        }
    }
    if (run == null || (!run.running && (run.finishedAt == hiddenAt || run.finishedAt == 0L))) return
    // results older than a few minutes are not news any more
    if (!run.running && System.currentTimeMillis() - run.finishedAt > 5 * 60_000L) return
    val (text, tone) = when {
        run.running -> stringResource(
            R.string.fav_running, run.name, run.current?.let { partWord(context, it) } ?: "",
        ) to p.ink
        run.failed.isNotEmpty() -> stringResource(
            R.string.fav_partial, run.name, run.failed.joinToString(", ") { partWord(context, it) },
        ) to p.fault
        else -> stringResource(R.string.fav_done, run.name) to p.live
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, tone, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(
            when {
                run.running -> GlyphKind.Refresh
                run.failed.isNotEmpty() -> GlyphKind.Warning
                else -> GlyphKind.Check
            },
            color = tone,
            size = 16.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = p.ink)
            if (!run.running && run.skipped.isNotEmpty()) {
                Text(
                    stringResource(R.string.fav_skipped, run.skipped.joinToString(", ") { partWord(context, it) }),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                )
            }
        }
        if (!run.running && run.failed.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Key(
                text = stringResource(R.string.dismiss),
                onClick = { hiddenAt = run.finishedAt },
                tone = KeyTone.Ghost,
                dense = true,
            )
        }
    }
}

/**
 * Every favourite of this pair: apply, rename, delete, and save the current setting under a
 * name. Opened with [saving] the name field is already up, with a default name.
 */
@Composable
fun FavouritesSheet(
    scope: AidScope,
    aids: List<Pair<String, AidUiState?>>,
    saving: Boolean,
    onDismiss: () -> Unit,
) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val snapshot = pairSnapshot(scope, aids)
    val canSave = aids.any { it.second != null } && (snapshot.program != null || snapshot.volumes.isNotEmpty())
    var draft by remember { mutableStateOf(if (saving && canSave) defaultName(context, scope, aids, snapshot.program) else null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val busy = scope.favouriteRun?.running == true

    PanelSheet(onDismiss = onDismiss) {
        HairRule()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.fav_title).uppercase(), style = InstrumentType.plate, color = p.inkMuted)
        Text(stringResource(R.string.fav_title), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Text(
            stringResource(R.string.fav_local),
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        val current = draft
        if (current != null) {
            NameField(stringResource(R.string.fav_name), current) { draft = it }
            val preview = Favourites.capture("", current, snapshot, 0L)
            Text(
                stringResource(R.string.fav_saves, summary(context, preview, scope, aids)),
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(
                    text = stringResource(R.string.fav_save_key),
                    onClick = {
                        val name = current.trim().ifEmpty { defaultName(context, scope, aids, snapshot.program) }
                        val now = System.currentTimeMillis()
                        val f = Favourites.capture(java.util.UUID.randomUUID().toString(), name, snapshot, now)
                        scope.saveFavourites(scope.favourites + f)
                        draft = null
                    },
                    tone = KeyTone.Primary,
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                )
                Key(
                    text = stringResource(R.string.fav_cancel),
                    onClick = { draft = null },
                    tone = KeyTone.Ghost,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
        } else if (canSave) {
            Key(
                text = stringResource(R.string.fav_save),
                onClick = { draft = defaultName(context, scope, aids, snapshot.program) },
                glyph = GlyphKind.Plus,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
        }

        if (scope.favourites.isEmpty()) {
            Text(stringResource(R.string.fav_empty), style = MaterialTheme.typography.bodyMedium, color = p.ink)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .then(if (scope.favourites.isNotEmpty()) Modifier.border(1.dp, p.rule, RoundedCornerShape(2.dp)) else Modifier),
        ) {
            scope.favourites.forEachIndexed { i, f ->
                if (i > 0) HairRule()
                val matched = Favourites.matches(f, snapshot)
                Column(Modifier.fillMaxWidth().background(p.plate).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (matched) {
                            Glyph(GlyphKind.Check, color = p.ink, size = 16.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(f.name, style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.weight(1f))
                        if (matched) Stamp(stringResource(R.string.fav_matched), tone = p.ink)
                    }
                    Text(
                        summary(context, f, scope, aids),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.inkMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    if (renaming == f.id) {
                        NameField(stringResource(R.string.fav_name), renameText) { renameText = it }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Key(
                                text = stringResource(R.string.fav_save_key),
                                onClick = {
                                    val name = renameText.trim()
                                    if (name.isNotEmpty()) {
                                        scope.saveFavourites(scope.favourites.map { if (it.id == f.id) it.copy(name = name) else it })
                                    }
                                    renaming = null
                                },
                                tone = KeyTone.Primary,
                                modifier = Modifier.weight(1f),
                            )
                            Key(
                                text = stringResource(R.string.fav_cancel),
                                onClick = { renaming = null },
                                tone = KeyTone.Ghost,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Key(
                                text = stringResource(R.string.fav_apply),
                                onClick = {
                                    scope.send(Cmd.ApplyFavourite(f))
                                    onDismiss()
                                },
                                enabled = !busy && aids.any { it.second != null },
                                dense = true,
                                contentDescription = stringResource(R.string.fav_apply_cd, f.name),
                                modifier = Modifier.weight(1f),
                            )
                            Key(
                                text = stringResource(R.string.fav_rename),
                                onClick = {
                                    renaming = f.id
                                    renameText = f.name
                                    confirmDelete = null
                                },
                                tone = KeyTone.Ghost,
                                dense = true,
                                contentDescription = stringResource(R.string.fav_rename_cd, f.name),
                                modifier = Modifier.weight(1f),
                            )
                            val confirming = confirmDelete == f.id
                            Key(
                                text = stringResource(if (confirming) R.string.fav_delete_confirm else R.string.fav_delete),
                                onClick = {
                                    if (confirming) {
                                        scope.saveFavourites(scope.favourites.filter { it.id != f.id })
                                        confirmDelete = null
                                    } else {
                                        confirmDelete = f.id
                                    }
                                },
                                tone = if (confirming) KeyTone.Danger else KeyTone.Ghost,
                                dense = true,
                                contentDescription = stringResource(R.string.fav_delete_cd, f.name),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Key(
            text = stringResource(R.string.dismiss),
            onClick = onDismiss,
            tone = KeyTone.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NameField(label: String, value: String, onChange: (String) -> Unit) {
    val p = LocalPanel.current
    Text(label.uppercase(), style = InstrumentType.plate.copy(fontSize = 9.sp), color = p.inkMuted, modifier = Modifier.padding(bottom = 4.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(p.recess)
            .border(1.dp, p.rule, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(40)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

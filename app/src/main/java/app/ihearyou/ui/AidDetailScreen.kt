package app.ihearyou.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R
import app.ihearyou.service.Cmd
import app.ihearyou.ui.theme.InstrumentType
import app.ihearyou.ui.theme.LocalPanel

@Composable
fun AidDetailScreen(scope: AidScope, address: String, onBack: () -> Unit) {
    val p = LocalPanel.current
    val context = LocalContext.current
    val info = scope.infos[address]
    val state = scope.states[address]
    val side = scope.sides[address]
    val cros = info?.cros == true
    val linked = state != null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle(
            title = stringResource(R.string.aid_with_side, sideTitle(context, side)),
            subtitle = stringResource(R.string.join_dot, roleLong(context, info), modelWord(context, info, scope.names[address])),
            onBack = onBack,
            trailing = if (scope.sample) {
                { Stamp(stringResource(R.string.sample)) }
            } else {
                null
            },
        )

        Plate(title = stringResource(R.string.aid_detail_identification), modifier = Modifier.padding(top = 18.dp)) {
            InfoRow(stringResource(R.string.aid_detail_side), sideWord(context, side))
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_role), stringResource(if (cros) R.string.role_long_cros else R.string.role_long_receiver))
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_model), info?.model ?: "—", mono = true)
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_firmware), info?.firmware ?: "—", mono = true)
            HairRule()
            InfoRow(
                stringResource(R.string.aid_detail_serial),
                info?.serial?.takeIf { it.isNotBlank() }?.takeLast(4) ?: "—",
                mono = true,
            )
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_address), address, mono = true)
            HairRule()
            InfoRow(
                stringResource(R.string.aid_detail_session),
                when {
                    scope.sample -> stringResource(R.string.aid_detail_session_sample)
                    linked -> stringResource(R.string.aid_detail_session_open)
                    else -> stringResource(R.string.aid_detail_session_not_linked)
                },
                tone = if (scope.sample) p.inkMuted else if (linked) p.live else p.inkMuted,
            )
        }

        Plate(title = stringResource(R.string.aid_detail_readings), modifier = Modifier.padding(top = 12.dp)) {
            InfoRow(stringResource(R.string.aid_detail_attenuation), hexByte(state?.volume), mono = true)
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_level_shown), levelOf(state?.volume)?.let { stringResource(R.string.aid_detail_level_of_99, it) } ?: "—")
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_battery_state), batteryWord(context, state?.battery, state?.hiState))
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_battery_level), state?.battery?.let { stringResource(R.string.aid_detail_battery_of_10, it) } ?: "—", mono = true)
            HairRule()
            if (!cros) {
                InfoRow(stringResource(R.string.aid_detail_program_index), state?.program?.let { stringResource(R.string.program_short, it + 1) } ?: "—")
                HairRule()
            }
            InfoRow(
                stringResource(R.string.aid_detail_stream),
                streamWord(context, state?.streamStatus).let { word ->
                    streamKindWord(context, state?.streamType)?.let { stringResource(R.string.join_dot, word, it) } ?: word
                },
            )
            HairRule()
            InfoRow(stringResource(R.string.aid_detail_signal), state?.rssi?.let { stringResource(R.string.dbm, it) } ?: "—", mono = true)
            HairRule()
            if (!cros && state?.tap != null) {
                InfoRow(stringResource(R.string.aid_detail_tap_bits), hexBits(state.tap) + "  " + hexByte(state.tap), mono = true)
                HairRule()
            }
            InfoRow(stringResource(R.string.aid_detail_read_at), preciseClockOf(state?.confirmedAt), mono = true)
        }

        if (!cros) {
            if (state?.tap != null || scope.sample) Plate(title = stringResource(R.string.aid_detail_tap_control), modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    stringResource(R.string.aid_detail_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                )
                Spacer(Modifier.height(6.dp))
                val tap = state?.tap ?: 0
                ToggleKey(
                    checked = tap and 0x02 != 0,
                    label = stringResource(R.string.aid_detail_tap_calls),
                    onChange = { scope.send(Cmd.Tap(address, calls = it)) },
                    enabled = linked,
                )
                HairRule()
                ToggleKey(
                    checked = tap and 0x01 != 0,
                    label = stringResource(R.string.aid_detail_tap_program),
                    onChange = { scope.send(Cmd.Tap(address, program = it)) },
                    enabled = linked,
                )
            }

            Plate(title = stringResource(R.string.aid_detail_enhancer), modifier = Modifier.padding(top = 12.dp)) {
                val features = state?.features.orEmpty()
                if (features.isEmpty()) {
                    Text(
                        stringResource(R.string.aid_detail_enhancer_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.inkMuted,
                    )
                } else {
                    Text(
                        stringResource(R.string.aid_detail_enhancer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.inkMuted,
                    )
                    Spacer(Modifier.height(4.dp))
                    features.groupBy { it.name }.entries.forEachIndexed { index, entry ->
                        if (index > 0) HairRule()
                        InfoRow(entry.key, entry.value.joinToString(", ") { it.value })
                    }
                }
            }
        } else {
            Plate(title = stringResource(R.string.aid_detail_transmitter), modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    stringResource(R.string.aid_detail_transmitter_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.inkMuted,
                )
            }
        }

        if (!scope.sample) {
            var confirm by remember { mutableStateOf(false) }
            Plate(title = stringResource(R.string.aid_detail_forget), modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    if (confirm) stringResource(R.string.aid_detail_forget_confirm_hint)
                    else stringResource(R.string.aid_detail_forget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                )
                Spacer(Modifier.height(8.dp))
                Key(
                    text = if (confirm) stringResource(R.string.aid_detail_forget_now) else stringResource(R.string.aid_detail_forget_this),
                    onClick = {
                        if (confirm) {
                            scope.send(Cmd.Forget(address))
                            confirm = false
                        } else {
                            confirm = true
                        }
                    },
                    tone = if (confirm) KeyTone.Danger else KeyTone.Neutral,
                    contentDescription = if (confirm) stringResource(R.string.aid_detail_forget_confirm_cd) else stringResource(R.string.aid_detail_forget_this),
                )
            }
        }
    }
}

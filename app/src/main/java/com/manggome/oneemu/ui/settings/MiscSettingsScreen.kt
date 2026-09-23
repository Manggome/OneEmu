package com.manggome.oneemu.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Speed
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FF_SPEEDS = Settings.FF_SPEEDS // 0 = unlimited

/** Buttons 연사 can tap, in the order the picker lists them. */
private val TURBO_BUTTONS = listOf(
    "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
    "L" to Buttons.L, "R" to Buttons.R, "L2" to Buttons.L2, "R2" to Buttons.R2,
)

/** 연사 and 빨리감기: the two ways of making a game go faster than your thumbs. */
@Composable
internal fun MiscSettingsScreen(onBack: () -> Unit) {
    val turboMask = rememberPref(Settings.Keys.turboMask, Settings.DEFAULT_TURBO_MASK)
    val turboRate = rememberPref(Settings.Keys.turboRate, Settings.DEFAULT_TURBO_RATE)
    val ffSpeed = rememberPref(Settings.Keys.fastForwardSpeed, Settings.DEFAULT_FF_SPEED)
    val unlimited = stringResource(R.string.misc_ff_unlimited)

    SettingsScaffold(title = stringResource(R.string.settings_misc), onBack = onBack) {
        SectionHeader(stringResource(R.string.input_turbo))
        MultiChoiceRow(
            title = stringResource(R.string.input_turbo_buttons),
            options = TURBO_BUTTONS.map { it.first },
            selected = TURBO_BUTTONS.indices.filter { turboMask.value and TURBO_BUTTONS[it].second != 0 }.toSet(),
            onChange = { picked -> turboMask.set(picked.fold(0) { acc, i -> acc or TURBO_BUTTONS[i].second }) },
            emptyLabel = stringResource(R.string.input_turbo_none),
            icon = Icons.Outlined.Bolt,
        )
        ChoiceRow(
            title = stringResource(R.string.input_turbo_rate),
            options = Settings.TURBO_RATES.map { stringResource(R.string.input_turbo_rate_value, it) },
            selectedIndex = Settings.TURBO_RATES.indexOf(turboRate.value)
                .let { if (it < 0) Settings.TURBO_RATES.indexOf(Settings.DEFAULT_TURBO_RATE) else it },
            onSelect = { turboRate.set(Settings.TURBO_RATES[it]) },
            icon = Icons.Outlined.Speed,
        )
        NoteText(stringResource(R.string.input_turbo_note))
        SettingsDivider()

        SectionHeader(stringResource(R.string.settings_section_ff))
        ChoiceRow(
            title = stringResource(R.string.misc_ff_speed),
            options = FF_SPEEDS.map { if (it == 0) unlimited else "${it}×" },
            selectedIndex = FF_SPEEDS.indexOf(ffSpeed.value).let { if (it < 0) FF_SPEEDS.indexOf(Settings.DEFAULT_FF_SPEED) else it },
            onSelect = { ffSpeed.set(FF_SPEEDS[it]) },
            icon = Icons.Outlined.FastForward,
        )
    }
}

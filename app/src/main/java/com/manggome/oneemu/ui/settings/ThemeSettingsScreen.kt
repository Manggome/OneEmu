package com.manggome.oneemu.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.theme.ThemeMode
import com.manggome.oneemu.ui.theme.ThemePreset
import com.manggome.oneemu.ui.theme.ThemePresets
import com.manggome.oneemu.ui.theme.ThemeSettings
import com.manggome.oneemu.ui.theme.rememberResolvedTheme

/**
 * Theme picker: light/dark mode, a grid of preset cards each painted in its own colours, plus
 * AMOLED black and Material You toggles. Writes only the keys in [ThemeSettings]; the running
 * theme updates live because [com.manggome.oneemu.ui.theme.OneEmuTheme] observes them.
 */
@Composable
internal fun ThemeSettingsScreen(onBack: () -> Unit) {
    val presetId = rememberPref(ThemeSettings.themeId, ThemePresets.DEFAULT_ID)
    val modeKey = rememberPref(ThemeSettings.themeMode, ThemeMode.SYSTEM.key)
    val amoled = rememberPref(ThemeSettings.themeAmoled, false)
    val dynamic = rememberPref(ThemeSettings.themeDynamic, false)

    val mode = ThemeMode.fromKey(modeKey.value)
    val systemDark = isSystemInDarkTheme()
    val previewDark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamicSupported = ThemeSettings.dynamicSupported
    val dynamicOn = dynamic.value && dynamicSupported
    val selectedId = if (dynamicOn) ThemePresets.dynamic.id else ThemePresets.byId(presetId.value).id

    fun select(preset: ThemePreset) {
        if (preset.id == ThemePresets.dynamic.id) {
            dynamic.set(true)
        } else {
            presetId.set(preset.id)
            if (dynamic.value) dynamic.set(false)
        }
    }

    SettingsScaffold(title = stringResource(R.string.settings_theme), onBack = onBack) {
        SectionHeader(stringResource(R.string.theme_mode))
        ModeSelector(mode = mode, onSelect = { modeKey.set(it.key) })

        SectionHeader(stringResource(R.string.theme_presets))
        PresetGrid(
            presets = ThemePresets.all.filter { it.available },
            selectedId = selectedId,
            dark = previewDark,
            amoled = amoled.value,
            onSelect = ::select,
        )
        NoteText(stringResource(R.string.theme_preview_note))

        SectionHeader(stringResource(R.string.theme_options))
        SwitchRow(
            title = stringResource(R.string.theme_amoled),
            subtitle = stringResource(R.string.theme_amoled_desc),
            icon = Icons.Outlined.Contrast,
            checked = amoled.value,
            onCheckedChange = { amoled.set(it) },
        )
        if (dynamicSupported) {
            SwitchRow(
                title = stringResource(R.string.theme_dynamic_toggle),
                subtitle = stringResource(R.string.theme_dynamic_toggle_desc),
                icon = Icons.Outlined.AutoAwesome,
                checked = dynamic.value,
                onCheckedChange = { dynamic.set(it) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeSelector(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_mode_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_mode_light),
        ThemeMode.DARK to stringResource(R.string.theme_mode_dark),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun PresetGrid(
    presets: List<ThemePreset>,
    selectedId: String,
    dark: Boolean,
    amoled: Boolean,
    onSelect: (ThemePreset) -> Unit,
) {
    // Plain rows instead of LazyVerticalGrid: the screen is already a vertical scroller.
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        presets.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        selected = preset.id == selectedId,
                        dark = dark,
                        amoled = amoled,
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Card painted entirely in the preset's own resolved scheme: a mini app bar, three list rows and
 * a FAB, so the user sees the real combination before tapping.
 */
@Composable
private fun PresetCard(
    preset: ThemePreset,
    selected: Boolean,
    dark: Boolean,
    amoled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = rememberResolvedTheme(preset, dark, amoled)
    val scheme = resolved.scheme
    val shape = RoundedCornerShape(14.dp)
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    Column(
        modifier
            .clip(shape)
            .border(border, shape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(112.dp).background(scheme.background)) {
            MiniAppMock(scheme)
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.theme_selected), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(scheme.primary, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(preset.nameRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(preset.descRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MiniAppMock(scheme: ColorScheme) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        // App bar: title text + a primary-coloured action dot.
        Row(
            Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(scheme.surface).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.theme_preview_title),
                color = scheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(8.dp).background(scheme.primary, CircleShape))
        }
        Spacer(Modifier.height(6.dp))
        // List rows: icon square, a title bar and a muted subtitle bar.
        repeat(3) { i ->
            Row(
                Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceContainerHigh).padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(if (i == 0) scheme.primary else scheme.onSurfaceVariant, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.weight(if (i == 1) 0.7f else 1f).height(4.dp).background(scheme.onSurface.copy(alpha = 0.85f), CircleShape))
                Spacer(Modifier.weight(if (i == 1) 0.6f else 0.3f))
                Box(Modifier.width(14.dp).height(3.dp).background(scheme.onSurfaceVariant, CircleShape))
            }
            if (i < 2) Spacer(Modifier.height(4.dp))
        }
    }
    // FAB
    Box(
        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(22.dp).background(scheme.primary, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(9.dp).height(2.dp).background(scheme.onPrimary, CircleShape))
        Box(Modifier.width(2.dp).height(9.dp).background(scheme.onPrimary, CircleShape))
    }
}

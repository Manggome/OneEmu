package com.manggome.oneemu.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.WipiCompat
import com.manggome.oneemu.library.WipiCompat.Status
import com.manggome.oneemu.ui.theme.OneEmuColors

private val StatusGreen = Color(0xFF5CC489)
private val StatusLime = Color(0xFFA6D96A)
private val StatusAmber = Color(0xFFF0B429)
private val StatusOrange = Color(0xFFF08A29)
private val StatusGrey = Color(0xFF8A8F98)

private fun statusColor(s: Status): Color = when (s) {
    Status.PERFECT -> StatusGreen
    Status.PLAYABLE -> StatusLime
    Status.MENU -> StatusAmber
    Status.INTRO -> StatusOrange
    Status.BROKEN -> OneEmuColors.Danger
    Status.UNKNOWN -> StatusGrey
}

private fun statusLabel(s: Status): Int = when (s) {
    Status.PERFECT -> R.string.wipi_status_perfect
    Status.PLAYABLE -> R.string.wipi_status_playable
    Status.MENU -> R.string.wipi_status_menu
    Status.INTRO -> R.string.wipi_status_intro
    Status.BROKEN -> R.string.wipi_status_broken
    Status.UNKNOWN -> R.string.wipi_status_unknown
}

private fun statusDesc(s: Status): Int = when (s) {
    Status.PERFECT -> R.string.wipi_status_perfect_desc
    Status.PLAYABLE -> R.string.wipi_status_playable_desc
    Status.MENU -> R.string.wipi_status_menu_desc
    Status.INTRO -> R.string.wipi_status_intro_desc
    Status.BROKEN -> R.string.wipi_status_broken_desc
    Status.UNKNOWN -> R.string.wipi_status_unknown_desc
}

/**
 * The compatibility verdict for a feature-phone game file: the cached one immediately when the file is unchanged,
 * otherwise null until the background hash finishes. Also asks for the day's list refresh (throttled inside).
 */
@Composable
fun rememberWipiCompat(game: GameEntity, refresh: Int = 0): WipiCompat.Verdict? {
    val context = LocalContext.current
    val compat = remember { WipiCompat.get(context) }
    val initial = remember(game.path) { compat.cached(game.path) }
    val state = produceState(initialValue = initial, key1 = game.path, key2 = refresh) {
        value = compat.resolve(game.path)
    }
    return state.value
}

/** Small coloured dot for list and grid items; grey for 미확인, nothing while the first check runs. */
@Composable
fun WipiCompatDot(verdict: WipiCompat.Verdict?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    if (verdict == null) return
    val label = stringResource(statusLabel(verdict.status))
    Box(
        modifier
            .size(size)
            .background(statusColor(verdict.status), CircleShape)
            .border(1.dp, Color(0x99000000), CircleShape)
            .semantics { contentDescription = label },
    )
}

/** Detail-screen card: status, what it means, whether this exact file was the one checked, and notes. */
@Composable
fun WipiCompatCard(game: GameEntity) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (WipiCompat.get(context).refreshOnline()) refresh++
    }
    val verdict = rememberWipiCompat(game, refresh)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.wipi_compat_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (verdict == null) {
                Text(stringResource(R.string.wipi_compat_checking), style = MaterialTheme.typography.bodyMedium, color = OneEmuColors.OnSurfaceMuted)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                WipiCompatDot(verdict, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(statusLabel(verdict.status)),
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor(verdict.status),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(statusDesc(verdict.status)), style = MaterialTheme.typography.bodyMedium)

            val entry = verdict.entry
            when (verdict.match) {
                WipiCompat.Match.EXACT -> {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.wipi_match_exact), style = MaterialTheme.typography.bodySmall, color = StatusGreen)
                }
                WipiCompat.Match.OTHER_EDITION -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.wipi_match_other, entry?.title.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusAmber,
                    )
                }
                WipiCompat.Match.NONE -> {}
            }

            Spacer(Modifier.height(12.dp))
            if (entry != null) {
                InfoRow(stringResource(R.string.wipi_info_game), listOf(entry.title, entry.carrier).filter { it.isNotBlank() }.joinToString(" · "))
                if (entry.tested.isNotBlank()) InfoRow(stringResource(R.string.wipi_info_tested), entry.tested)
                InfoRow(
                    stringResource(R.string.wipi_info_method),
                    stringResource(if (entry.verified == "manual") R.string.wipi_method_manual else R.string.wipi_method_auto),
                )
                entry.note?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.wipi_info_note), it) }
            }
            verdict.identity?.let { InfoRow(stringResource(R.string.wipi_info_hash), it.sha256, mono = true) }
        }
    }
}

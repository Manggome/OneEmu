package com.manggome.oneemu.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.ArcadeCoreRouter
import com.manggome.oneemu.library.ArcadeDatDownloader
import com.manggome.oneemu.library.ArcadeRename
import com.manggome.oneemu.library.ArcadeRomCheck
import com.manggome.oneemu.library.ArcadeRomChecker
import com.manggome.oneemu.ui.common.ConfirmDialog
import com.manggome.oneemu.ui.theme.OneEmuColors
import kotlinx.coroutines.launch

private val StatusOk = Color(0xFF5CC489)
private val StatusWarn = Color(0xFFF0B429)

private fun severityColor(s: ArcadeRomCheck.Severity): Color = when (s) {
    ArcadeRomCheck.Severity.OK -> StatusOk
    ArcadeRomCheck.Severity.WARN -> StatusWarn
    ArcadeRomCheck.Severity.ERROR -> OneEmuColors.Danger
}

/**
 * Lazily resolves an arcade zip (which MAME core's DAT lists it + that core's report). Returns the cached
 * resolution immediately when the file is unchanged, otherwise null until the bounded background check
 * (Dispatchers.IO, 2 at a time) finishes. [refresh] > 0 forces a re-check.
 */
@Composable
fun rememberArcadeResolution(game: GameEntity, refresh: Int = 0): ArcadeRomCheck.Resolution? {
    val context = LocalContext.current
    val checker = remember { ArcadeRomChecker.get(context) }
    val initial = remember(game.path) { checker.cached(game.path) }
    val state = produceState(initialValue = initial, key1 = game.path, key2 = refresh) {
        if (value == null || refresh > 0) value = checker.resolve(game.path, force = refresh > 0)
    }
    return state.value
}

/**
 * Small coloured dot (green OK / amber needs something / red cannot run) for list and grid items. A game whose
 * core is known to crash the process ([ArcadeRomCheck.Resolution.knownUnstable]) is red too, whatever its report
 * says — the report status itself is left alone.
 */
@Composable
fun ArcadeStatusDot(resolution: ArcadeRomCheck.Resolution?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    if (resolution == null) return
    val report = resolution.report
    val label = stringResource(
        when {
            resolution.knownUnstable -> R.string.lib_arcade_badge_unstable
            report.severity == ArcadeRomCheck.Severity.OK -> R.string.lib_arcade_badge_ok
            report.severity == ArcadeRomCheck.Severity.WARN -> R.string.lib_arcade_badge_warn
            else -> R.string.lib_arcade_badge_error
        },
    )
    val color = if (resolution.knownUnstable) OneEmuColors.Danger else severityColor(report.severity)
    Box(
        modifier
            .size(size)
            .background(color, CircleShape)
            .border(1.dp, Color(0x99000000), CircleShape)
            .semantics { contentDescription = label },
    )
}

/** Detail-screen card: status, explanation, missing files, samples/DB helpers and the help text. */
@Composable
fun ArcadeRomCard(game: GameEntity, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val checker = remember { ArcadeRomChecker.get(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val resolution = rememberArcadeResolution(game, refresh)
    val report = resolution?.report
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var confirmRename by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_arcade_check_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { checker.invalidate(game.path); refresh++ }) { Text(stringResource(R.string.lib_arcade_recheck)) }
            }
            if (resolution == null || report == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.lib_arcade_checking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArcadeStatusDot(resolution, size = 12.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(checker.statusText(resolution), style = MaterialTheme.typography.titleSmall, color = severityColor(report.severity))
                }
                // "실행 코어: MAME 2010 (MAME 0.139 롬셋)" — which bundled MAME the zip is routed to, and why it left
                // the preferred core ("MAME 2003-Plus에서는 미완성 드라이버라 MAME 2010으로 실행") when it did.
                checker.runCoreText(resolution)?.let { line ->
                    Spacer(Modifier.height(4.dp))
                    Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    checker.routeReasonText(resolution)?.let { why ->
                        Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // "이 게임의 기판(세가 ST-V)은 현재 포함된 MAME 코어에서 강제 종료됩니다 …" — the chosen core crashes too.
                    checker.unstableNote(resolution)?.let { warn ->
                        Spacer(Modifier.height(2.dp))
                        Text(warn, style = MaterialTheme.typography.labelSmall, color = OneEmuColors.Danger)
                    }
                }
                // "에뮬레이션 상태: 양호 / 불완전(…) / 미완성(실행 불안정)" from the chosen core's DAT.
                checker.driverStatusText(resolution)?.let { line ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (resolution.driverStatus) {
                            ArcadeRomCheck.DriverStatus.PRELIMINARY -> OneEmuColors.Danger
                            ArcadeRomCheck.DriverStatus.IMPERFECT -> StatusWarn
                            else -> StatusOk
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(checker.explanation(resolution), style = MaterialTheme.typography.bodyMedium)
                val notes = checker.companionNotes(resolution)
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    for (n in notes) Text("• $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (report.status == ArcadeRomCheck.Status.RENAME_SUGGESTED && report.suggestedName != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { confirmRename = true }) { Text(stringResource(R.string.lib_arcade_rename)) }
                }
                if (report.issues.isNotEmpty()) {
                    TextButton(onClick = { showFiles = !showFiles }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(
                            if (showFiles) stringResource(R.string.lib_arcade_files_hide)
                            else stringResource(R.string.lib_arcade_files_show, report.issues.size),
                        )
                    }
                    if (showFiles) IssueList(report)
                }
                if (report.game?.needsSamples == true) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    SamplesSection(report, checker.samplesDir(resolution.coreId ?: ArcadeCoreRouter.MAME2003PLUS))
                }
            }

            // cheat.dat / hiscore.dat downloads are MAME 2003-Plus only; hide them for games routed to MAME 2010.
            if (resolution?.coreId == null || resolution.coreId == ArcadeCoreRouter.MAME2003PLUS) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                DatSection(checker, onMessage)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            TextButton(onClick = { showHelp = !showHelp }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.lib_arcade_help_title))
            }
            if (showHelp) HelpSection()
        }
    }

    if (confirmRename && resolution != null && report?.suggestedName != null) {
        val current = java.io.File(game.path).name
        val target = "${report.suggestedName}.zip"
        ConfirmDialog(
            title = stringResource(R.string.lib_arcade_rename_title),
            text = stringResource(R.string.lib_arcade_rename_confirm, current, target),
            confirmText = stringResource(R.string.lib_arcade_rename),
            onConfirm = {
                scope.launch {
                    when (val r = ArcadeRename.apply(game, resolution)) {
                        // The detail screen observes the row, so the new path re-triggers rememberArcadeResolution.
                        is ArcadeRename.Result.Done -> onMessage(context.getString(R.string.lib_arcade_rename_done, r.newFile.name))
                        is ArcadeRename.Result.TargetExists -> onMessage(context.getString(R.string.lib_arcade_rename_exists, r.target.name))
                        is ArcadeRename.Result.Failed -> onMessage(context.getString(R.string.lib_arcade_rename_failed, r.target.name))
                        ArcadeRename.Result.SourceMissing -> onMessage(context.getString(R.string.lib_arcade_rename_missing))
                    }
                }
            },
            onDismiss = { confirmRename = false },
        )
    }
}

@Composable
private fun IssueList(report: ArcadeRomCheck.Report) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in report.issues.take(MAX_ISSUE_ROWS)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    i.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (i.missing) "${stringResource(R.string.lib_arcade_file_missing)} · ${i.owner}.zip"
                    else "${stringResource(R.string.lib_arcade_file_found, i.foundCrc ?: "?")} ≠ ${i.expectedCrc}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (i.missing) OneEmuColors.Danger else StatusWarn,
                )
            }
        }
        if (report.issues.size > MAX_ISSUE_ROWS) {
            Text("… +${report.issues.size - MAX_ISSUE_ROWS}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SamplesSection(report: ArcadeRomCheck.Report, samplesDir: java.io.File) {
    Text(stringResource(R.string.lib_arcade_samples_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (report.samplesPresent) {
        Text(stringResource(R.string.lib_arcade_samples_present, report.sampleZip ?: ""), style = MaterialTheme.typography.bodySmall, color = StatusOk)
    } else {
        Text(stringResource(R.string.lib_arcade_samples_path, "${report.sampleZip}.zip"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        PathText(samplesDir.absolutePath)
    }
}

/** One shared downloader so progress survives leaving the screen. */
private var sharedDownloader: ArcadeDatDownloader? = null

@Composable
private fun DatSection(checker: ArcadeRomChecker, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val downloader = remember { sharedDownloader ?: ArcadeDatDownloader(checker.datDir).also { sharedDownloader = it } }
    val state by downloader.state.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(downloader.installed()) }
    LaunchedEffect(state) {
        when (val s = state) {
            is ArcadeDatDownloader.State.Done -> { installed = downloader.installed(); onMessage(context.getString(R.string.lib_arcade_dat_done, s.files.size)) }
            is ArcadeDatDownloader.State.Failed -> { installed = downloader.installed(); onMessage(context.getString(R.string.lib_arcade_dat_failed, s.message)) }
            else -> Unit
        }
    }
    Text(stringResource(R.string.lib_arcade_dat_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.lib_arcade_dat_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Text(
        if (installed.isEmpty()) stringResource(R.string.lib_arcade_dat_none) else stringResource(R.string.lib_arcade_dat_installed, installed.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = if (installed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else StatusOk,
    )
    Spacer(Modifier.height(6.dp))
    val running = state as? ArcadeDatDownloader.State.Running
    if (running != null) {
        if (running.progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            if (running.progress < 0f) stringResource(R.string.lib_arcade_dat_connecting, running.source)
            else stringResource(R.string.lib_arcade_dat_progress, (running.progress * 100).toInt()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (installed.size < ArcadeDatDownloader.DAT_FILES.size) {
        OutlinedButton(onClick = {
            // App scope: the download keeps going if the user leaves the screen.
            OneEmuApp.get().appScope.launch { runCatching { downloader.download() } }
        }) { Text(stringResource(R.string.lib_arcade_dat_download)) }
    }
}

@Composable
private fun HelpSection() {
    val uri = LocalUriHandler.current
    Column(Modifier.padding(top = 4.dp)) {
        Text(stringResource(R.string.lib_arcade_help_body), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { uri.openUri(DOCS_URL) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.lib_arcade_link_docs), fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = { uri.openUri(DOCS_2010_URL) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.lib_arcade_link_docs_2010), fontWeight = FontWeight.Medium)
        }
        TextButton(onClick = { uri.openUri(MAMEDEV_ROMS_URL) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.lib_arcade_link_mamedev), fontWeight = FontWeight.Medium)
        }
    }
}

private const val MAX_ISSUE_ROWS = 40
private const val DOCS_URL = "https://docs.libretro.com/library/mame2003_plus/"
private const val DOCS_2010_URL = "https://docs.libretro.com/library/mame_2010/"
private const val MAMEDEV_ROMS_URL = "https://www.mamedev.org/roms/"

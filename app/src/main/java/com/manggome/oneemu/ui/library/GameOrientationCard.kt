package com.manggome.oneemu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.emu.GameOrientation
import kotlinx.coroutines.launch

/** 화면 방향 for one game ([GameOrientation]); the same choice the in-game settings show. */
@Composable
internal fun GameOrientationCard(game: GameEntity) {
    val context = LocalContext.current
    val settings = OneEmuApp.get().settings
    val scope = rememberCoroutineScope()
    val effective by remember(game.id, game.path) { GameOrientation.observe(context.applicationContext, settings, game) }
        .collectAsState(GameOrientation.Effective(GameOrientation.DEFAULT))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.orient_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                if (effective.fromCatalog) {
                    stringResource(R.string.orient_catalog_default, stringResource(effective.orientation.labelRes)) + " " +
                        stringResource(R.string.orient_card_desc)
                } else {
                    stringResource(R.string.orient_card_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            GameOrientation.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { GameOrientation.set(settings, game.id, option) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == effective.orientation, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(option.labelRes), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(option.descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

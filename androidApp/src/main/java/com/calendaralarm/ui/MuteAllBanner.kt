package com.calendaralarm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.calendaralarm.R

/**
 * 「すべてのアラームをミュート」中に予定・アラームの両タブへ出す帯 (#272)。
 * 形は予定タブの StatusBanner と同じ。StatusBanner は private で他の変更と重なるので共通化しない。
 * 「解除」は設定を書き換えるだけで、再同期は設定の見張り (watchScheduleSettings, #255) が行う
 */
@Composable
internal fun MuteAllBanner(onUnmute: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.mute_all_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUnmute) { Text(stringResource(R.string.mute_all_banner_unmute)) }
        }
    }
}

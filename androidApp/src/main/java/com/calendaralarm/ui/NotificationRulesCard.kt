package com.calendaralarm.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.engine.NotificationRuleTemplates
import com.calendaralarm.shared.ai.NotificationRuleGenerator
import com.calendaralarm.shared.ai.OpenRouterApi
import com.calendaralarm.shared.model.NotificationRuleSpec
import kotlinx.coroutines.launch

/**
 * 通知アラームの設定カード: 有効化・権限誘導・ルール一覧・追加
 * (手動/テンプレート/AI生成の3経路。元アプリと同じ導線)。
 */
@Composable
fun NotificationRulesCard(settings: SettingsRepository, prefs: SettingsRepository.Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val listenerGranted = NotificationManagerCompat
        .getEnabledListenerPackages(context).contains(context.packageName)

    var editDraft by remember { mutableStateOf<NotificationRuleSpec?>(null) }
    var aiText by remember { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            // 有効化 + 権限状態
            IconSettingRow(Icons.Default.Notifications) {
                Text(
                    if (listenerGranted) "通知アラーム" else "通知アラーム (要権限)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = prefs.notificationAlarmEnabled,
                    onCheckedChange = {
                        scope.launch { settings.setNotificationAlarmEnabled(it) }
                    },
                )
            }
            if (!listenerGranted) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("通知へのアクセスを許可") }
            }

            // ルール一覧
            prefs.notificationRules.forEach { rule ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                rule.packageName,
                                rule.requiredKeywords.joinToString("+").ifBlank { null },
                                rule.anyKeywords.joinToString("/").ifBlank { null },
                            ).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            settings.setNotificationRules(
                                prefs.notificationRules - rule,
                            )
                        }
                    }) { Icon(Icons.Default.Delete, contentDescription = "削除") }
                }
            }

            // 追加ボタン群
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { editDraft = NotificationRuleSpec(name = "") }) {
                    Text("＋ルール")
                }
                TextButton(onClick = { aiText = "" }) {
                    Text("AI生成")
                }
            }
            // テンプレート (横スクロール相当に複数行で表示)
            NotificationRuleTemplates.ALL.chunked(3).forEach { row ->
                Row {
                    row.forEach { t ->
                        TextButton(onClick = {
                            scope.launch {
                                settings.setNotificationRules(
                                    prefs.notificationRules + t,
                                )
                            }
                        }) { Text(t.name, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    // 手動/AI生成で埋めた下書きを編集する共通ダイアログ
    editDraft?.let { draft ->
        RuleEditDialog(
            initial = draft,
            onDismiss = { editDraft = null },
            onSave = { rule ->
                scope.launch {
                    settings.setNotificationRules(prefs.notificationRules + rule)
                }
                editDraft = null
            },
        )
    }

    // AI生成: 要望文を入れて生成→下書きに流し込む
    aiText?.let { text ->
        var working by remember { mutableStateOf(false) }
        var genError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { aiText = null },
            title = { Text("AIでルール生成") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { aiText = it },
                        placeholder = { Text("例: Gmailで重要なメールが来たら鳴らして") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    genError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && text.isNotBlank(),
                    onClick = {
                        working = true
                        scope.launch {
                            val key = prefs.openRouterApiKey
                            if (key.isNullOrBlank()) {
                                genError = "OpenRouter キーが未設定です"
                                working = false
                                return@launch
                            }
                            val spec = NotificationRuleGenerator(
                                OpenRouterApi(apiKey = key, model = prefs.openRouterModel),
                            ).generate(text)
                            working = false
                            if (spec == null) genError = "生成に失敗しました"
                            else { editDraft = spec; aiText = null }
                        }
                    },
                ) { Text(if (working) "生成中…" else "生成") }
            },
            dismissButton = { TextButton(onClick = { aiText = null }) { Text("戻る") } },
        )
    }
}

/** ルールの手動編集ダイアログ。キーワードはカンマ区切り、曜日はチップ。 */
@Composable
private fun RuleEditDialog(
    initial: NotificationRuleSpec,
    onDismiss: () -> Unit,
    onSave: (NotificationRuleSpec) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var pkg by remember { mutableStateOf(initial.packageName.orEmpty()) }
    var req by remember { mutableStateOf(initial.requiredKeywords.joinToString(",")) }
    var any by remember { mutableStateOf(initial.anyKeywords.joinToString(",")) }
    var exc by remember { mutableStateOf(initial.excludeKeywords.joinToString(",")) }
    var days by remember { mutableStateOf(initial.daysOfWeek.toSet()) }
    // 時間帯は "HH:mm" テキスト (空=終日)。開始=終了でも終日扱いなので空推奨
    var startText by remember {
        mutableStateOf(initial.startMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "")
    }
    var endText by remember {
        mutableStateOf(initial.endMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "")
    }

    fun parseHm(s: String): Int? = s.trim().takeIf { it.isNotEmpty() }?.let {
        "^([01]?[0-9]|2[0-3]):([0-5][0-9])$".toRegex().matchEntire(it)
            ?.let { m -> m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通知ルール") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("名前") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = pkg, onValueChange = { pkg = it },
                    label = { Text("対象アプリ (空=全アプリ)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = req, onValueChange = { req = it },
                    label = { Text("必須キーワード (カンマ区切り)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = any, onValueChange = { any = it },
                    label = { Text("任意キーワード") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = exc, onValueChange = { exc = it },
                    label = { Text("除外キーワード") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it.take(5) },
                        label = { Text("開始 HH:mm") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it.take(5) },
                        label = { Text("終了 HH:mm") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 曜日 (1=月..7=日)
                Row {
                    listOf("月", "火", "水", "木", "金", "土", "日").forEachIndexed { i, d ->
                        val dow = i + 1
                        FilterChip(
                            selected = dow in days,
                            onClick = {
                                days = if (dow in days) days - dow else days + dow
                            },
                            label = { Text(d, maxLines = 1) },
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        NotificationRuleSpec(
                            name = name.trim(),
                            packageName = pkg.trim().ifBlank { null },
                            requiredKeywords = req.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            anyKeywords = any.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            excludeKeywords = exc.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            daysOfWeek = days.sorted(),
                            startMinuteOfDay = parseHm(startText),
                            endMinuteOfDay = parseHm(endText),
                        ),
                    )
                },
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("戻る") } },
    )
}

package com.calendaralarm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.calendaralarm.data.InviteDefaultMigration
import com.calendaralarm.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 招待フィルタの既定変更 (#155) の移行。前から使っている人 (何か保存済みで招待キーが無い) は
 * 今までの「全部オン」を残し、新しい人は新しい既定 (承諾のみ) のまま。判定は一度だけ。
 * 実際の DataStore を一時ファイルで開き直して確かめる。
 */
class InviteDefaultMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val otherKey = intPreferencesKey("alarm_volume_percent")

    /** [file] を開いて [block] を実行し、閉じる (同じファイルを同時に 2 つ開かないため) */
    private fun <T> open(file: File, migrate: Boolean, upgrade: Boolean = false, block: suspend (androidx.datastore.core.DataStore<Preferences>) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(
            migrations = if (migrate) listOf(InviteDefaultMigration { upgrade }) else emptyList(),
            scope = scope,
            produceFile = { file },
        )
        return try {
            runBlocking { block(store) }
        } finally {
            // 閉じ切るまで待ってから次を開く (同じファイルを 2 つの DataStore で同時に開かないため)
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test
    fun `前から使っている人は招待キーが無くても今までの全部オンが残る`() {
        val file = File(tmp.root, "existing.preferences_pb")
        open(file, migrate = false) { s -> s.edit { it[otherKey] = 50 } }
        val mask = open(file, migrate = true) { s -> s.data.first()[SettingsRepository.KEY_INVITE_MASK] }
        assertEquals(SettingsRepository.LEGACY_INVITE_MASK, mask)
    }

    @Test
    fun `新しい人は保存せず新しい既定のまま`() {
        val file = File(tmp.root, "fresh.preferences_pb")
        val mask = open(file, migrate = true) { s -> s.data.first()[SettingsRepository.KEY_INVITE_MASK] }
        assertNull(mask)
    }

    @Test
    fun `新しい人が後で別の設定を変えても全部オンにはしない`() {
        val file = File(tmp.root, "later.preferences_pb")
        open(file, migrate = true) { s -> s.edit { it[otherKey] = 50 } }
        val mask = open(file, migrate = true) { s -> s.data.first()[SettingsRepository.KEY_INVITE_MASK] }
        assertNull("判定は最初の 1 回だけ", mask)
    }

    @Test
    fun `招待キーを保存済みの人の選択はそのまま`() {
        val file = File(tmp.root, "chosen.preferences_pb")
        open(file, migrate = false) { s -> s.edit { it[SettingsRepository.KEY_INVITE_MASK] = 3 } }
        val mask = open(file, migrate = true) { s -> s.data.first()[SettingsRepository.KEY_INVITE_MASK] }
        assertEquals(3, mask)
    }

    @Test
    fun `設定を一度も変えていない前からの利用者も更新なら全部オンが残る`() {
        val file = File(tmp.root, "untouched.preferences_pb")
        val mask = open(file, migrate = true, upgrade = true) { s -> s.data.first()[SettingsRepository.KEY_INVITE_MASK] }
        assertEquals(SettingsRepository.LEGACY_INVITE_MASK, mask)
    }
}

package app.codeg.android.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure mapping tests for the AppSettings decode (batch v123 regression follow-up):
 * the Activity device-swipe toggle defaults to false and stands alone.
 * 批v150: the Folders twin toggle retired with the Folders tab; the floating-ball
 * prefs joined the same guard in batch v126.
 */
class AppSettingsStoreTest {

    @Test
    fun `defaults are off`() {
        val s = AppSettings()
        assertFalse(s.activityDeviceSwipe)
    }

    @Test
    fun `floating ball defaults are on medium and system font`() {
        val s = AppSettings()
        assertTrue("the ball is a primary nav surface — ships on", s.floatBallEnabled)
        assertEquals("medium", s.floatBallSize)
        assertEquals(1f, s.fontScale)
    }

    @Test
    fun `size tiers coerce to the three known ids`() {
        assertTrue(FLOAT_BALL_SIZES.containsAll(listOf("small", "medium", "large")))
        assertEquals(3, FLOAT_BALL_SIZES.size)
    }

    @Test
    fun `cycle depth defaults to five and clamps to the one-to-ten band`() {
        // 批v160 循环切最近会话: the depth is the ring size of recent sessions the
        // ball walks. Loader clamps corrupt prefs into the band, so the store can
        // never hand the hub a 0 / negative / huge ring.
        val s = AppSettings()
        assertEquals(5, s.floatBallCycleDepth)
        assertEquals(1, FLOAT_BALL_CYCLE_MIN)
        assertEquals(10, FLOAT_BALL_CYCLE_MAX)
    }

    @Test
    fun `session alert defaults are notify on and keepalive off`() {
        // 批v160 W8: the "agent needs you" notification ships ON (it is the point
        // of the batch); the battery-costing foreground service ships OFF —
        // opting in is the user's call, never a default.
        val s = AppSettings()
        assertTrue(s.notifyInteractiveEnabled)
        assertFalse(s.keepAliveEnabled)
    }

    @Test
    fun `task result and device event notification defaults are both on`() {
        // 系统级通知体系: the second/third tiers ship ON like the interactive
        // tier — they are the point of the feature; each channel switch is
        // independent so users mute outcomes without losing alerts (or voice).
        val s = AppSettings()
        assertTrue(s.notifyTaskResultsEnabled)
        assertTrue(s.notifyDeviceEventsEnabled)
    }

    @Test
    fun `preferences key names stay distinct`() {
        // The Key constants pin the wire keys: a copy-paste that made two toggles
        // read the same DataStore key would pass compile and silently corrupt
        // settings. Same risk class for the batch-v126 ball keys (pos vs bindings
        // especially — both JSON strings). The retired folders_device_swipe key is
        // NOT asserted: an old install's stored value is simply ignored now.
        // (Kotlin hoists companion constants to static fields on the OUTER class —
        // the Companion object's own field table is empty; probe-verified.)
        val keys = AppSettingsStore::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .map { it.isAccessible = true; (it.get(null) as androidx.datastore.preferences.core.Preferences.Key<*>).name }
        assertEquals(1, keys.count { it == "activity_device_swipe" })
        assertEquals(1, keys.count { it == "float_ball_enabled" })
        assertEquals(1, keys.count { it == "float_ball_size" })
        assertEquals(1, keys.count { it == "font_scale" })
        assertEquals(1, keys.count { it == "float_ball_pos" })
        assertEquals(1, keys.count { it == "float_ball_bindings" })
        assertEquals(1, keys.count { it == "float_ball_cycle_depth" })
        assertEquals(1, keys.count { it == "notify_interactive_enabled" })
        assertEquals(1, keys.count { it == "notify_task_results" })
        assertEquals(1, keys.count { it == "notify_device_events" })
        assertEquals(1, keys.count { it == "keep_alive_enabled" })
        assertEquals(1, keys.count { it == "task_swipe_mode" })
        // 批v160 W9: the session chip row's dimension, order and count sort.
        assertEquals(1, keys.count { it == "session_chip_mode" })
        assertEquals(1, keys.count { it == "project_chip_order" })
        assertEquals(1, keys.count { it == "project_chip_sort_by_count" })
        // 批v161: task-board shape + the two per-layout column counts.
        assertEquals(1, keys.count { it == "task_board_layout" })
        assertEquals(1, keys.count { it == "task_board_kanban_columns" })
        assertEquals(1, keys.count { it == "task_board_list_columns" })
        // 批v172 D3: the three per-interface font-scale keys.
        assertEquals(1, keys.count { it == "session_font_scale" })
        assertEquals(1, keys.count { it == "inbox_font_scale" })
        assertEquals(1, keys.count { it == "taskhub_font_scale" })
        // And no key collides with another at all.
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `task swipe mode defaults to segments and only two modes are legal`() {
        // 批v160 W5: the task board's horizontal swipe steps the four status
        // columns by default; switching devices is the explicit opt-in. The
        // loader coerces unknown stored values back to the default, so a corrupt
        // pref can never strand the board.
        val s = AppSettings()
        assertEquals(TASK_SWIPE_SEGMENTS, s.taskSwipeMode)
        assertEquals(setOf("segments", "devices"), TASK_SWIPE_MODES)
        assertTrue(TASK_SWIPE_MODES.contains(TASK_SWIPE_SEGMENTS))
        assertTrue(TASK_SWIPE_MODES.contains(TASK_SWIPE_DEVICES))
    }

    @Test
    fun `task board layout defaults to a two-column kanban and a single list`() {
        // 批v161: phones open on the double-column kanban (the whole board in two
        // pages); list mode keeps the classic single column. Both counts are legal
        // only in 1..2 — the store coerces corrupt prefs into that band.
        val s = AppSettings()
        assertEquals(TASK_BOARD_KANBAN, s.taskBoardLayout)
        assertEquals(setOf("kanban", "list"), TASK_BOARD_LAYOUTS)
        assertEquals(2, s.taskBoardKanbanColumns)
        assertEquals(1, s.taskBoardListColumns)
    }

    @Test
    fun `interface font scales default to follow global`() {
        // 批v172 D3: 0 = follow the global font scale — existing users keep
        // today's look until they set a per-interface value.
        val s = AppSettings()
        assertEquals(0f, s.sessionFontScale)
        assertEquals(0f, s.inboxFontScale)
        assertEquals(0f, s.taskhubFontScale)
    }

    @Test
    fun `v161 board layout and column counts survive a real datastore roundtrip`() = runBlocking {
        // Read-side guard for the three v161 keys: a wrong Key, a missing map
        // branch or an unclamped int would silently show the defaults forever.
        val tmp = TemporaryFolder()
        tmp.create()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "settings.preferences_pb")
            }
            val store = AppSettingsStore(dataStore)
            store.setTaskBoardLayout(TASK_BOARD_LIST)
            store.setTaskBoardKanbanColumns(1)
            store.setTaskBoardListColumns(2)
            val s = store.settings.first()
            assertEquals(TASK_BOARD_LIST, s.taskBoardLayout)
            assertEquals(1, s.taskBoardKanbanColumns)
            assertEquals(2, s.taskBoardListColumns)
            // Corrupt layout + out-of-band counts coerce instead of poisoning the board.
            store.setTaskBoardLayout("nonsense")
            store.setTaskBoardKanbanColumns(9)
            store.setTaskBoardListColumns(-3)
            val coerced = store.settings.first()
            assertEquals(TASK_BOARD_KANBAN, coerced.taskBoardLayout)
            assertEquals(2, coerced.taskBoardKanbanColumns)
            assertEquals(1, coerced.taskBoardListColumns)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `session chip row defaults to devices, manual order empty, count sort off`() {
        // 批v160 W9: the chip row keeps its pre-W9 shape for existing users —
        // device chips, creation-ordered projects, no count sort.
        val s = AppSettings()
        assertEquals(SESSION_CHIP_DEVICES, s.sessionChipMode)
        assertEquals(setOf("devices", "projects"), SESSION_CHIP_MODES)
        assertTrue(SESSION_CHIP_MODES.contains(SESSION_CHIP_PROJECTS))
        assertTrue(s.projectChipOrder.isEmpty())
        assertFalse(s.projectChipSortByCount)
    }

    @Test
    fun `w9 chip keys survive a real datastore roundtrip`() = runBlocking {
        // R2-A P3: the pure-default tests above never exercise the READ side
        // (prefs → AppSettings). A real DataStore round-trip pins the three W9
        // keys end to end, including the unknown-mode coerce branch.
        val tmp = TemporaryFolder()
        tmp.create()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "settings.preferences_pb")
            }
            val store = AppSettingsStore(dataStore)
            store.setSessionChipMode("projects")
            store.setProjectChipOrder(listOf("a", "b"))
            store.setProjectChipSortByCount(true)
            val s = store.settings.first()
            assertEquals("projects", s.sessionChipMode)
            assertEquals(listOf("a", "b"), s.projectChipOrder)
            assertTrue(s.projectChipSortByCount)
            // Corrupt input coerces instead of poisoning the chip row.
            store.setSessionChipMode("nonsense")
            assertEquals(SESSION_CHIP_DEVICES, store.settings.first().sessionChipMode)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `w5 swipe mode and w8 toggles survive a real datastore roundtrip`() = runBlocking {
        // Same read-side gap the w9 test closes, for the W5 board swipe and the
        // W8 notification/keepalive toggles — including the corrupt-mode coerce.
        val tmp = TemporaryFolder()
        tmp.create()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "settings.preferences_pb")
            }
            val store = AppSettingsStore(dataStore)
            store.setTaskSwipeMode(TASK_SWIPE_DEVICES)
            store.setNotifyInteractiveEnabled(false)
            store.setKeepAliveEnabled(true)
            val s = store.settings.first()
            assertEquals(TASK_SWIPE_DEVICES, s.taskSwipeMode)
            assertFalse(s.notifyInteractiveEnabled)
            assertTrue(s.keepAliveEnabled)
            // Corrupt swipe mode coerces back to the default instead of
            // stranding the board.
            store.setTaskSwipeMode("nonsense")
            assertEquals(TASK_SWIPE_SEGMENTS, store.settings.first().taskSwipeMode)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `notification channel toggles survive a real datastore roundtrip`() = runBlocking {
        // 系统级通知体系: read-side guard for the two new channel switches —
        // the pure-default test above never exercises prefs → AppSettings.
        // Plain booleans: no coerce branch to probe, just the persisted value.
        val tmp = TemporaryFolder()
        tmp.create()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "settings.preferences_pb")
            }
            val store = AppSettingsStore(dataStore)
            store.setNotifyTaskResultsEnabled(false)
            store.setNotifyDeviceEventsEnabled(false)
            var s = store.settings.first()
            assertFalse(s.notifyTaskResultsEnabled)
            assertFalse(s.notifyDeviceEventsEnabled)
            store.setNotifyTaskResultsEnabled(true)
            store.setNotifyDeviceEventsEnabled(true)
            s = store.settings.first()
            assertTrue(s.notifyTaskResultsEnabled)
            assertTrue(s.notifyDeviceEventsEnabled)
            // The untouched interactive/keepalive pair stays at its own values.
            assertTrue(s.notifyInteractiveEnabled)
            assertFalse(s.keepAliveEnabled)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `project chip order drops blank ids and keeps a reordered sequence`() = runBlocking {
        // R3-B defense in depth: a blank between commas (corrupt pref) is
        // filtered on BOTH the write and the read side, so orderedProjectChips
        // never even sees it.
        val tmp = TemporaryFolder()
        tmp.create()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "settings.preferences_pb")
            }
            val store = AppSettingsStore(dataStore)
            store.setProjectChipOrder(listOf("b", "", "a"))
            store.setProjectChipSortByCount(true)
            val s = store.settings.first()
            assertEquals(listOf("b", "a"), s.projectChipOrder)
            assertTrue(s.projectChipSortByCount)
            store.setProjectChipSortByCount(false)
            assertFalse(store.settings.first().projectChipSortByCount)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }

    @Test
    fun `v172 interface font scales survive a real datastore roundtrip`() = runBlocking {
        // 批v172 D3: read-side guard for the three per-interface scales — the
        // setter band (0.5..2) and the 0 = follow-global sentinel must both
        // survive prefs → AppSettings, or the shell would scale the wrong tab.
        val tmp = TemporaryFolder()
        tmp.create()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "settings.preferences_pb")
            }
            val store = AppSettingsStore(dataStore)
            store.setSessionFontScale(1.3f)
            store.setInboxFontScale(0.85f)
            store.setTaskhubFontScale(1.15f)
            val s = store.settings.first()
            assertEquals(1.3f, s.sessionFontScale)
            assertEquals(0.85f, s.inboxFontScale)
            assertEquals(1.15f, s.taskhubFontScale)
            // Out-of-band values clamp into 0.5..2; ≤0 means "follow global".
            store.setSessionFontScale(9f)
            store.setInboxFontScale(-3f)
            store.setTaskhubFontScale(0f)
            val coerced = store.settings.first()
            assertEquals(2f, coerced.sessionFontScale)
            assertEquals(0f, coerced.inboxFontScale)
            assertEquals(0f, coerced.taskhubFontScale)
        } finally {
            scope.cancel()
            tmp.delete()
        }
    }
}

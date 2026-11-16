package app.codeg.android.feature.main

import app.codeg.android.core.datastore.migrateHomeTabOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批v152 把会话恢复成底栏标签后，持久化的 tab 顺序/起始页仍需安静收敛：
 * 未知路由（v150 时代的 folders/search）被丢弃，chats 重新成为合法项。
 * 另覆盖底栏显隐与生效起始页两个纯函数——它们是「隐藏后不会锁死设置页」
 * 与「隐藏的起始页自动回退」的唯一保证。
 */
class TabMigrationTest {

    @Test
    fun `saved order keeps survivors and re-appends the rest`() {
        val migrated = HomeTab.orderedBy(listOf("chats", "folders", "activity", "search", "settings"))
        // "chats"/"activity"/"settings" survive in saved order; the demoted push
        // routes are dropped; the remaining tabs append in enum order (批v180:
        // DEVICES sits between TASKS and SETTINGS in the enum, so it lands
        // before SETTINGS in the appended tail).
        assertEquals(
            listOf(HomeTab.CHATS, HomeTab.ACTIVITY, HomeTab.SETTINGS, HomeTab.INBOX, HomeTab.TASKS, HomeTab.DEVICES),
            migrated,
        )
    }

    @Test
    fun `an empty saved order keeps enum order`() {
        assertEquals(HomeTab.entries.toList(), HomeTab.orderedBy(emptyList()))
    }

    @Test
    fun `unknown routes are dropped without resurrecting push surfaces`() {
        val migrated = HomeTab.orderedBy(listOf("chats", "search", "bogus", "inbox"))
        // known=[CHATS, INBOX] first, then every remaining tab in enum order
        // (批v180: DEVICES rides the enum tail, before SETTINGS).
        assertEquals(
            listOf(HomeTab.CHATS, HomeTab.INBOX, HomeTab.ACTIVITY, HomeTab.TASKS, HomeTab.DEVICES, HomeTab.SETTINGS),
            migrated,
        )
    }

    @Test
    fun `chats resolves as a tab again`() {
        assertEquals(HomeTab.CHATS, HomeTab.byRoute("chats"))
        // Demoted push routes are not tabs — they fall back to the workbench.
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("folders"))
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("search"))
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute(null))
    }

    @Test
    fun `new and surviving start routes resolve directly`() {
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("activity"))
        assertEquals(HomeTab.INBOX, HomeTab.byRoute("inbox"))
        assertEquals(HomeTab.TASKS, HomeTab.byRoute("tasks"))
        assertEquals(HomeTab.SETTINGS, HomeTab.byRoute("settings"))
    }

    @Test
    fun `hidden tabs leave the bar but settings can never be hidden`() {
        val tabs = visibleTabs(
            savedOrder = emptyList(),
            hidden = listOf("chats", "inbox", "tasks", "settings"),
        )
        // settings is force-kept (it is the way back to the switches), chats/inbox/
        // tasks are gone from the bar.
        assertEquals(listOf(HomeTab.ACTIVITY, HomeTab.SETTINGS), tabs)
    }

    @Test
    fun `hiding everything still yields a non-empty bar`() {
        val tabs = visibleTabs(
            savedOrder = emptyList(),
            hidden = HomeTab.entries.map { it.route },
        )
        assertEquals(listOf(HomeTab.SETTINGS), tabs)
    }

    @Test
    fun `a hidden start tab falls back to the first visible tab`() {
        val tabs = visibleTabs(savedOrder = emptyList(), hidden = listOf("activity"))
        assertEquals(HomeTab.CHATS, resolveStartTab("activity", tabs))
    }

    @Test
    fun `a visible start tab is preserved`() {
        val tabs = visibleTabs(savedOrder = emptyList(), hidden = listOf("activity"))
        assertEquals(HomeTab.INBOX, resolveStartTab("inbox", tabs))
    }

    @Test
    fun `batch v180 - the devices tab is opt-in and off by default`() {
        // Default (two-arg) calls keep the historical behaviour: DEVICES stays off
        // the bar until the caller passes the live toggle.
        val without = visibleTabs(savedOrder = emptyList(), hidden = emptyList())
        assertTrue(HomeTab.DEVICES !in without)
        val with = visibleTabs(savedOrder = emptyList(), hidden = emptyList(), devicesTabEnabled = true)
        assertTrue(HomeTab.DEVICES in with)
        // The persisted route resolves even while the tab is hidden — resolveStartTab
        // then falls back to the first visible tab (ACTIVITY leads the enum order).
        assertEquals(HomeTab.DEVICES, HomeTab.byRoute("devices"))
        assertEquals(HomeTab.ACTIVITY, resolveStartTab("devices", without))
    }

    @Test
    fun `the shipped default start tab is the workbench`() {
        assertEquals("activity", app.codeg.android.core.datastore.AppSettings.DEFAULT_START_TAB)
    }

    @Test
    fun `legacy order gains chats right after the workbench`() {
        // v150-era default: 会话 was not a tab, so the saved order has no "chats".
        // Inserting it after "activity" keeps the upgraded bar in the fresh order
        // instead of parking 会话 behind 设置 (the append-unknown-tabs rule).
        assertEquals(
            listOf("activity", "chats", "inbox", "tasks", "settings"),
            migrateHomeTabOrder(listOf("activity", "inbox", "tasks", "settings")),
        )
    }

    @Test
    fun `orders that already mention chats stay untouched`() {
        val saved = listOf("chats", "activity", "settings")
        assertEquals(saved, migrateHomeTabOrder(saved))
        assertEquals(emptyList<String>(), migrateHomeTabOrder(emptyList()))
    }

    @Test
    fun `legacy order without the workbench gets chats up front`() {
        // No "activity" anchor → prepend, so 会话 still leads the bar.
        assertEquals(
            listOf("chats", "folders", "settings"),
            migrateHomeTabOrder(listOf("folders", "settings")),
        )
    }
}

package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping tests for the AppSettings decode (batch v123 regression follow-up):
 * the Activity and Folders device-swipe toggles are two INDEPENDENT DataStore keys
 * — the folders one defaults to false and never inherits the activity one.
 */
class AppSettingsStoreTest {

    @Test
    fun `defaults are both off`() {
        val s = AppSettings()
        assertFalse(s.activityDeviceSwipe)
        assertFalse(s.foldersDeviceSwipe)
    }

    @Test
    fun `the two toggles are independent fields`() {
        val on = AppSettings(activityDeviceSwipe = true, foldersDeviceSwipe = true)
        val mixed = AppSettings(activityDeviceSwipe = true, foldersDeviceSwipe = false)
        val otherMixed = AppSettings(activityDeviceSwipe = false, foldersDeviceSwipe = true)
        assertTrue(on.activityDeviceSwipe && on.foldersDeviceSwipe)
        assertTrue(mixed.activityDeviceSwipe && !mixed.foldersDeviceSwipe)
        assertTrue(otherMixed.foldersDeviceSwipe && !otherMixed.activityDeviceSwipe)
    }

    @Test
    fun `preferences key names stay distinct`() {
        // The Key constants pin the wire keys: a copy-paste that made both toggles
        // read the same DataStore key would pass compile and silently reintroduce
        // the v123 "one switch drives both tabs" bug. (Kotlin hoists companion
        // constants to static fields on the OUTER class — the Companion object's
        // own field table is empty; probe-verified.)
        val keys = AppSettingsStore::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .map { it.isAccessible = true; (it.get(null) as androidx.datastore.preferences.core.Preferences.Key<*>).name }
        assertEquals(1, keys.count { it == "activity_device_swipe" })
        assertEquals(1, keys.count { it == "folders_device_swipe" })
    }
}

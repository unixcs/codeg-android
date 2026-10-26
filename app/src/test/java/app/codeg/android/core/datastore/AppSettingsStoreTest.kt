package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping tests for the AppSettings decode (batch v123 regression follow-up):
 * the Activity and Folders device-swipe toggles are two INDEPENDENT DataStore keys
 * — the folders one defaults to false and never inherits the activity one.
 * Batch v126 adds the floating-ball prefs to the same guard.
 */
class AppSettingsStoreTest {

    @Test
    fun `defaults are both off`() {
        val s = AppSettings()
        assertFalse(s.activityDeviceSwipe)
        assertFalse(s.foldersDeviceSwipe)
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
        // the v123 "one switch drives both tabs" bug. Same risk class for the
        // batch-v126 ball keys (pos vs bindings especially — both JSON strings).
        // (Kotlin hoists companion constants to static fields on the OUTER class —
        // the Companion object's own field table is empty; probe-verified.)
        val keys = AppSettingsStore::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .map { it.isAccessible = true; (it.get(null) as androidx.datastore.preferences.core.Preferences.Key<*>).name }
        assertEquals(1, keys.count { it == "activity_device_swipe" })
        assertEquals(1, keys.count { it == "folders_device_swipe" })
        assertEquals(1, keys.count { it == "float_ball_enabled" })
        assertEquals(1, keys.count { it == "float_ball_size" })
        assertEquals(1, keys.count { it == "font_scale" })
        assertEquals(1, keys.count { it == "float_ball_pos" })
        assertEquals(1, keys.count { it == "float_ball_bindings" })
        // And no key collides with another at all.
        assertEquals(keys.size, keys.toSet().size)
    }
}

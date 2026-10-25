package app.codeg.android.feature.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure arithmetic tests for the device-swipe step resolver (batch v123): Activity
 * counts the aggregate "All" as sequence index 0 (devices 1..n); Folders has no
 * All — devices are 0..n-1 and the ends fall through to the tab switch.
 */
class ResolveDeviceSwipeStepTest {

    private val ALL = DeviceSwipeVerdict.All
    private fun device(i: Int, n: Int) = DeviceSwipeVerdict.Device("idx:$i/$n")

    // ---- Activity (WITH_ALL): the pre-existing All → devices sequence ----

    @Test
    fun `activity next from All goes to first device`() {
        assertEquals(device(0, 3), resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 0, 3, +1))
    }

    @Test
    fun `activity previous from first device returns to All`() {
        assertEquals(ALL, resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 1, 3, -1))
    }

    @Test
    fun `activity previous from All falls to tab switch`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 0, 3, -1))
    }

    @Test
    fun `activity next from last device falls to tab switch`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 3, 3, +1))
    }

    @Test
    fun `activity mid-sequence only switches devices`() {
        // Sequence index 2 = device 1 (0 is All); previous lands on device 0.
        assertEquals(device(0, 3), resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 2, 3, -1))
        // Sequence index 1 = device 0; next lands on device 1.
        assertEquals(device(1, 3), resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 1, 3, +1))
    }

    // ---- Folders (DEVICES_ONLY): no All stop, ends fall through ----

    @Test
    fun `folders next from first device goes to second`() {
        assertEquals(device(1, 3), resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, 0, 3, +1))
    }

    @Test
    fun `folders previous from first device falls to tab switch`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, 0, 3, -1))
    }

    @Test
    fun `folders next from last device falls to tab switch`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, 2, 3, +1))
    }

    @Test
    fun `folders never emits All`() {
        // Walk the whole 3-device sequence both ways; every non-Tab verdict is a Device.
        for (i in 0..2) {
            val next = resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, i, 3, +1)
            if (next != DeviceSwipeVerdict.Tab) assertEquals(device(i + 1, 3), next)
        }
        val back = resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, 2, 3, -1)
        assertEquals(device(1, 3), back)
    }

    // ---- Edge shapes ----

    @Test
    fun `single device folders swipes always fall through`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, 0, 1, +1))
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(DeviceSwipeScope.DEVICES_ONLY, 0, 1, -1))
    }

    @Test
    fun `single device activity is All bookended`() {
        assertEquals(device(0, 1), resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 0, 1, +1))
        assertEquals(ALL, resolveDeviceSwipeStep(DeviceSwipeScope.WITH_ALL, 1, 1, -1))
    }
}

package app.codeg.android.core.data

import app.codeg.android.core.datastore.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure per-device bookkeeping behind the progressive aggregate fan-out (batch
 * v137). The UI derives its banners, retry seeding and the device-set signature
 * from these helpers, so their ordering and filtering rules are contractual.
 */
class DeviceLoadTest {

    private fun load(
        id: String,
        name: String = id,
        status: DeviceLoadStatus,
        error: String? = null,
    ) = DeviceLoad(id, name, status, error)

    @Test
    fun `failed names keep feed order`() {
        val states = listOf(
            load("tx", "tx", DeviceLoadStatus.OK),
            load("win", "win 11", DeviceLoadStatus.FAILED, "No response from win 11"),
            load("nas", "nas", DeviceLoadStatus.LOADING),
            load("dell", "dell", DeviceLoadStatus.FAILED, "No response from dell"),
        )

        assertEquals(listOf("win 11", "dell"), states.failedNames())
    }

    @Test
    fun `loading names keep feed order`() {
        val states = listOf(
            load("win", "win 11", DeviceLoadStatus.LOADING),
            load("tx", "tx", DeviceLoadStatus.OK),
            load("nas", "nas", DeviceLoadStatus.LOADING),
        )

        assertEquals(listOf("win 11", "nas"), states.loadingNames())
    }

    @Test
    fun `ok count ignores loading and failed devices`() {
        val states = listOf(
            load("tx", "tx", DeviceLoadStatus.OK),
            load("win", "win", DeviceLoadStatus.FAILED),
            load("nas", "nas", DeviceLoadStatus.LOADING),
            load("dell", "dell", DeviceLoadStatus.OK),
        )

        assertEquals(2, states.okCount())
    }

    @Test
    fun `empty round has no pending or failed names`() {
        val states = emptyList<DeviceLoad>()

        assertTrue(states.failedNames().isEmpty())
        assertTrue(states.loadingNames().isEmpty())
        assertEquals(0, states.okCount())
    }

    @Test
    fun `participating drops devices that left the aggregate`() {
        val states = listOf(
            load("tx", "tx", DeviceLoadStatus.OK),
            load("win", "win", DeviceLoadStatus.FAILED),
            load("nas", "nas", DeviceLoadStatus.LOADING),
        )

        // A device hidden/skipped/deleted mid-round must not linger as FAILED and
        // keep naming itself in the banner.
        assertEquals(
            listOf("tx", "win"),
            states.participating(setOf("tx", "win")).map { it.serverId },
        )
        assertTrue(states.participating(emptySet()).isEmpty())
    }

    @Test
    fun `retrying flips only the failed devices back to loading`() {
        val states = listOf(
            load("tx", "tx", DeviceLoadStatus.OK),
            load("win", "win 11", DeviceLoadStatus.FAILED, "No response from win 11"),
            load("nas", "nas", DeviceLoadStatus.FAILED, "No response from nas"),
        )

        val retried = states.retrying(setOf("win"))

        assertEquals(DeviceLoadStatus.OK, retried[0].status)
        assertEquals(DeviceLoadStatus.LOADING, retried[1].status)
        assertNull(retried[1].error)
        // A failed device outside the retry set keeps its verdict for the banner.
        assertEquals(DeviceLoadStatus.FAILED, retried[2].status)
        assertEquals("No response from nas", retried[2].error)
    }

    @Test
    fun `retry set of a settled round is a no-op`() {
        val states = listOf(load("tx", "tx", DeviceLoadStatus.OK))

        assertEquals(states, states.retrying(emptySet()))
    }

    @Test
    fun `retry set includes a device still loading`() {
        // Review #2 regression: a retry supersedes the running round, so a device
        // that has not settled yet must be re-asked — otherwise its fetch is
        // cancelled and never replaced (Chats has no poll to recover it).
        val states = listOf(
            load("tx", "tx", DeviceLoadStatus.OK),
            load("win", "win 11", DeviceLoadStatus.FAILED),
            load("nas", "nas", DeviceLoadStatus.LOADING),
        )

        assertEquals(setOf("win", "nas"), states.retryableIds())

        val retried = states.retrying(states.retryableIds())
        assertEquals(
            listOf(
                DeviceLoadStatus.OK,
                DeviceLoadStatus.LOADING,
                DeviceLoadStatus.LOADING,
            ),
            retried.map { it.status },
        )
    }

    @Test
    fun `retry set is empty once every device answered`() {
        val states = listOf(
            load("tx", "tx", DeviceLoadStatus.OK),
            load("nas", "nas", DeviceLoadStatus.OK),
        )

        assertTrue(states.retryableIds().isEmpty())
    }

    @Test
    fun `signature ignores hidden devices and tracks the skip flag`() {
        val visible = ServerProfile("tx", "tx", "http://tx:3080")
        val hidden = ServerProfile("win", "win 11", "http://win:3080", visible = false)
        val skipped = ServerProfile("nas", "nas", "http://nas:3080", includeInAll = false)

        val signature = listOf(visible, hidden, skipped).aggregateDeviceSignature()

        // Hidden devices are not part of the fan-out at all; a skipped device stays
        // in the signature (with includeInAll=false) so toggling the flag re-fans-out
        // immediately instead of waiting for the next poll.
        assertEquals(
            "tx:tx:http://tx:3080:true|nas:nas:http://nas:3080:false",
            signature,
        )
    }

    @Test
    fun `signature changes on rename reopen and skip toggle`() {
        val base = ServerProfile("tx", "tx", "http://tx:3080")
        val signature = listOf(base).aggregateDeviceSignature()

        assertEquals(signature, listOf(base).aggregateDeviceSignature())
        assertTrue(signature != listOf(base.copy(name = "tx-new")).aggregateDeviceSignature())
        assertTrue(signature != listOf(base.copy(baseUrl = "http://tx:3081")).aggregateDeviceSignature())
        assertTrue(signature != listOf(base.copy(includeInAll = false)).aggregateDeviceSignature())
    }

    @Test
    fun `deadlines descend from a single device to the aggregate`() {
        // The skeleton only decides when to stop the full-screen spinner, so it must
        // fire well before the first per-device deadline; the single-device page is
        // wider than the aggregate because the user asked for that device
        // explicitly — and both stay far below OkHttp's 30s.
        assertTrue(AGG_SKELETON_DELAY_MS in 1..AGG_DEVICE_TIMEOUT_MS)
        assertTrue(AGG_DEVICE_TIMEOUT_MS < SINGLE_DEVICE_TIMEOUT_MS)
        assertTrue(SINGLE_DEVICE_TIMEOUT_MS < 10_000L)
        // The folder lookup is a nested, shorter deadline: a slow /folders must not
        // consume the device's whole budget and cost its rows.
        assertTrue(FOLDER_LOOKUP_TIMEOUT_MS in 1..AGG_DEVICE_TIMEOUT_MS)
    }
}

package app.codeg.android.core.data

import app.codeg.android.core.datastore.ServerProfile

/**
 * Where one device stands in an aggregate fan-out (batch v137). The aggregate
 * feeds used to be all-or-nothing (`awaitAll`), so a powered-off device held the
 * whole first screen until its 10s deadline. Each device now commits its own
 * state as soon as it settles, and the UI derives its banner from these entries.
 */
enum class DeviceLoadStatus { LOADING, OK, FAILED }

/** One device's state inside an aggregate round. [error] is only set for FAILED. */
data class DeviceLoad(
    val serverId: String,
    val name: String,
    val status: DeviceLoadStatus,
    val error: String? = null,
)

/**
 * Per-device deadline for the aggregate feeds. Tailscale direct is usually
 * <200ms and DERP relay 300–800ms; 3s covers a cold device without letting a
 * powered-off one stall the list. Single-device pages get a slightly wider
 * [SINGLE_DEVICE_TIMEOUT_MS] because the user asked for that device explicitly.
 */
const val AGG_DEVICE_TIMEOUT_MS: Long = 3_000L
const val SINGLE_DEVICE_TIMEOUT_MS: Long = 4_000L

/**
 * How long an aggregate may stay completely empty before it stops showing the
 * full-screen spinner and paints the list frame with per-device "connecting"
 * placeholders instead.
 */
const val AGG_SKELETON_DELAY_MS: Long = 600L

/** Folder-name lookup gets its own, shorter budget inside the per-device deadline:
 *  a slow `/folders` must not cost the device's rows (batch v137 review #2). */
const val FOLDER_LOOKUP_TIMEOUT_MS: Long = 800L

/** Names of the devices that failed this round, in feed order. */
fun List<DeviceLoad>.failedNames(): List<String> =
    filter { it.status == DeviceLoadStatus.FAILED }.map { it.name }

/** Names of the devices still being fetched this round, in feed order. */
fun List<DeviceLoad>.loadingNames(): List<String> =
    filter { it.status == DeviceLoadStatus.LOADING }.map { it.name }

/** How many devices returned data this round. */
fun List<DeviceLoad>.okCount(): Int = count { it.status == DeviceLoadStatus.OK }

/**
 * Drop devices that no longer participate in the aggregate (hidden, skipped or
 * deleted). A retry seeds from the previous round, so without this a device that
 * was skipped mid-round would linger as FAILED forever and keep naming itself in
 * the banner.
 */
fun List<DeviceLoad>.participating(ids: Set<String>): List<DeviceLoad> =
    filter { it.serverId in ids }

/**
 * Devices a retry must re-ask: everything that has not settled OK. Including a
 * still-LOADING device is deliberate — the retry supersedes the running round,
 * so leaving it out would cancel its fetch and never issue a new one.
 */
fun List<DeviceLoad>.retryableIds(): Set<String> =
    filter { it.status != DeviceLoadStatus.OK }.map { it.serverId }.toSet()

/**
 * Put every device that has not settled OK back into LOADING for a retry, leaving
 * the healthy ones untouched — the banner's retry must not re-ask devices that
 * already answered, but must not strand one that is still inside its window when
 * the retry supersedes the round either.
 */
fun List<DeviceLoad>.retrying(retryIds: Set<String>): List<DeviceLoad> =
    map { load ->
        if (load.serverId in retryIds) load.copy(status = DeviceLoadStatus.LOADING, error = null) else load
    }

/**
 * Identity of the aggregate fan-out set. Name/baseUrl are included so a rename
 * or endpoint edit re-fans-out, and includeInAll so toggling "skip in All"
 * re-computes immediately instead of waiting for the next 25s poll.
 */
fun List<ServerProfile>.aggregateDeviceSignature(): String =
    filter { it.visible }
        .joinToString("|") { "${it.id}:${it.name}:${it.baseUrl}:${it.includeInAll}" }

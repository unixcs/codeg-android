package app.codeg.android.core.datastore

import kotlinx.serialization.Serializable

/**
 * A saved codeg server connection. The auth token is NOT stored here — it lives
 * in the [app.codeg.android.core.security.SecretStore], keyed by [id]. Mirrors
 * the iOS `ServerProfile` (Keychain-backed token).
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    /**
     * Whether the device shows up in day-to-day surfaces (device bar, swipe ring,
     * aggregate activity feed, quick switches). Hidden devices stay saved and
     * re-selectable from the Servers management screen, which lists everything.
     */
    val visible: Boolean = true,
)

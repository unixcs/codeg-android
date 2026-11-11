package app.codeg.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.codeg.android.app.CodegApp
import app.codeg.android.core.common.DeepLinkBus
import app.codeg.android.core.common.LocaleManager
import app.codeg.android.feature.tv.TvApp
import app.codeg.android.feature.tv.isTvDevice
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. The whole UI is Compose; this activity enables
 * edge-to-edge and hosts the [CodegApp] root — or [TvApp] when the device
 * reports a television uiMode (single-APK TV routing, feat/tv M1).
 * [attachBaseContext] applies the user-chosen app language (see [LocaleManager]);
 * changing it calls `recreate()`. Incoming `codeg://` deep links are dispatched
 * to [DeepLinkBus].
 *
 * 系统级通知体系: cold start asks for POST_NOTIFICATIONS once (33+). The ask is
 * unconditional on the settings toggles — reading DataStore on the main thread
 * is not an option and the default is ON anyway; a user who turned notifications
 * off re-grants (harmlessly) through the Settings page's own request flow.
 * A denial does nothing here — the Settings toggle stays the retry entry point.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Registered as a field (must happen before RESUMED); the callback is
     *  deliberately empty — grant/deny changes nothing in this activity. */
    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { if (isTvDevice()) TvApp() else CodegApp() }
        DeepLinkBus.dispatch(intent?.data)
        maybeRequestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinkBus.dispatch(intent.data)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

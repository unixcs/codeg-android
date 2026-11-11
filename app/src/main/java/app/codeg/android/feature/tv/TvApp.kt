package app.codeg.android.feature.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import app.codeg.android.app.AppViewModel

/** True when the system reports a television uiMode (Android TV / TV boxes).
 *  Phones and Compose previews never report TELEVISION, so this is a reliable
 *  runtime switch for the single-APK TV routing done in [MainActivity]. */
fun Context.isTvDevice(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * TV root — the single-APK routing entry (MainActivity branches here on TV
 * uiMode). Wraps the shell in the TV-only dark theme; data flows through the
 * same [AppViewModel] the phone uses (Hilt graph reused as-is).
 */
@Composable
fun TvApp(appViewModel: AppViewModel = hiltViewModel()) {
    TvTheme {
        TvHome(appViewModel)
    }
}

package app.codeg.android.feature.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import app.codeg.android.R
import app.codeg.android.app.AppViewModel

/** True when the system reports a television uiMode (Android TV / TV boxes).
 *  Phones and Compose previews never report TELEVISION, so this is a reliable
 *  runtime switch for the single-APK TV routing done in [MainActivity]. */
fun Context.isTvDevice(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * TV root — feat/tv M1 slice 1 skeleton. Proves in one compile-green step:
 * the single-APK routing (MainActivity branches here on TV uiMode), the
 * androidx.tv tv-material dependency, and Hilt reuse of the phone [AppViewModel]
 * (the TV shell renders the same data layer — zero rewrite). The real shell
 * (left rail + read-only three screens + D-pad focus system) lands in the
 * upcoming M1 slices per the plan doc (929 30-方案 §十一).
 */
@Composable
fun TvApp(appViewModel: AppViewModel = hiltViewModel()) {
    val servers by appViewModel.allServers.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Codeg TV", style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.tv_shell_subtitle),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            val saved = servers.orEmpty()
            Text(
                stringResource(R.string.tv_shell_servers, saved.size),
                style = MaterialTheme.typography.titleMedium,
            )
            saved.forEach { profile ->
                Text(
                    "· " + profile.name,
                    modifier = Modifier.focusable(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

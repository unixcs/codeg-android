package app.codeg.android.core.notify

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app process is FOREGROUND right now, as one process-wide
 * [StateFlow] (批v161.3 R1: extracted from [app.codeg.android.CodegApplication]
 * so the notifiers can read it too). The 屏上抑制 objects ([ForegroundBoard],
 * [ForegroundConversation]) only know what is on screen — they cannot tell
 * "on screen" from "the app is behind the shade or another app", which is
 * exactly the state the notifications exist for. true = STARTED, not stopped —
 * the same definition the Application's observer shipped with (batch v130).
 */
@Singleton
class AppForeground @Inject constructor() {
    private val _fg = MutableStateFlow(true)
    val fg: StateFlow<Boolean> = _fg.asStateFlow()

    init {
        // Same registration the Application performed in onCreate. Safe at
        // construction time: the only consumer that resolves this eagerly is
        // the Application's field injection, which Hilt runs on the main
        // thread inside onCreate — after ProcessLifecycleOwner's startup
        // initializer, before any activity can report STARTED.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _fg.value = true }
            override fun onStop(owner: LifecycleOwner) { _fg.value = false }
        })
    }
}

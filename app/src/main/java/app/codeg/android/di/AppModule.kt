package app.codeg.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the process-lifetime [CoroutineScope] (see [AppModule.provideApplicationScope]). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * A scope that outlives any screen: work hung here (a prompt POST racing a
     * back-navigation) runs to completion even after the ViewModel that started
     * it is cleared. SupervisorJob so one failed child never cancels its
     * siblings; Default dispatcher — the session VM re-dispatches to Main where
     * it touches UI state.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

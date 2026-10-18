package app.myplayer

import android.app.Application
import app.myplayer.core.datastore.SettingsStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class MyPlayerApp : Application() {

    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var networkPolicy: NetworkPolicy

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 网络设置（明文 HTTP 开关）→ OkHttp 拦截器，进程级即时生效（任务 5-4）
        networkPolicy.bindSettings(settingsStore, appScope)
    }
}

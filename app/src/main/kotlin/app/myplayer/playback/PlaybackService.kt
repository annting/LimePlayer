package app.myplayer.playback

import android.content.Context
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.myplayer.core.playback.Media3PlaybackEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 播放引擎单例持有者（任务 2-4）。
 *
 * 引擎的生命周期从「播放页会话」升级为「应用进程」：前台服务 [PlaybackService] 需要长期持有
 * 同一个播放器来支撑媒体会话（通知栏控制 / 锁屏控制 / 画中画），所以不能再由 ViewModel
 * 按页创建释放。播放页退出走 [app.myplayer.core.playback.PlaybackEngine.stopSession]，
 * 进程退出时引擎随服务一起释放。
 */
@javax.inject.Singleton
class PlaybackEngineHolder @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: Context) {
    private var cached: Media3PlaybackEngine? = null

    /**
     * 当前引擎。**被释放过就重建**。
     *
     * 引擎是进程级单例，但系统可能在后台回收 [PlaybackService]（连同会话），
     * 万一它把引擎一起释放了（历史实现就是这么写的），这里必须能自己恢复，
     * 否则回到前台再播就会报「已释放的引擎不能再 prepare」（2026-10-21 真机反馈）。
     */
    val engine: Media3PlaybackEngine
        get() = cached?.takeIf { !it.isReleased } ?: Media3PlaybackEngine(context).also { cached = it }
}

/**
 * 前台媒体服务（`02` §6.4 / 任务 2-4）：把引擎的系统播放器包装成 [MediaSession]。
 *
 * Media3 框架据此自动提供：媒体样式通知栏（播放/暂停/ seek，随播放状态更新）、
 * 锁屏控制（系统行为，随会话免费获得 —— 专题打磨按用户要求推后）、蓝牙/耳机按钮事件。
 * 服务与 Activity 同进程，[PlaybackEngineHolder.engine] 单例共享。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var holder: PlaybackEngineHolder

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, holder.engine.platformPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // ⚠️ 只释放**会话**，不释放引擎里的 player：
        // 引擎是进程级单例（[PlaybackEngineHolder.engine]），系统在后台回收服务时
        // 若把 player 一起 release，回到前台就再也播不了（「已释放的引擎不能再 prepare」）。
        // 会话释放不会释放 player —— 下次绑定服务时 onCreate 会用**同一个** player 重建会话。
        session?.release()
        session = null
        super.onDestroy()
    }
}

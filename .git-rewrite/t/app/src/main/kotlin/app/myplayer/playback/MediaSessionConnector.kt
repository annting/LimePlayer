package app.myplayer.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页挂载时连接媒体会话（任务 2-4）。
 *
 * [PlaybackService] 是按需启动的：只有客户端用 [SessionToken] 建立连接，服务才会运行、
 * MediaSession 才会注册，通知栏 / 锁屏控制才存在。此 Composable 用 [MediaController]
 * 维持这条连接 —— 它同时还是外部控制播放的入口（2-7 连播 / 后续测试工具都会用到）。
 * 播放页销毁时释放连接；播放继续时服务仍以前台通知形式存续。
 */
@Composable
fun BindMediaSessionEffect() {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    LaunchedEffect(Unit) {
        try {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            // future 在主线程回调完成，get() 放到 IO 线程等待，不阻塞合成
            controller = withContext(Dispatchers.Default) { future.get() }
        } catch (e: Exception) {
            Log.w("LimePlayback", "MediaController 连接失败（通知栏控制将不可用）", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller?.release() }
    }
}

/** 供非 Compose 调用方（测试脚本 / 后续功能）获取 controller 的工厂。 */
object MediaSessionConnector {
    fun token(context: Context) = SessionToken(context, ComponentName(context, PlaybackService::class.java))
}

package app.myplayer.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.myplayer.core.designsystem.LocalAppColors
import app.myplayer.core.designsystem.LocalBottomNavSpace
import app.myplayer.core.designsystem.components.PageHeader

/** 连点窗口：超过这个间隔就重新计数，避免「随手点两下、明天再点一下就进去了」。 */
private const val TAP_WINDOW_MS = 1_500L

/** 触发隐私入口所需的点击数。 */
private const val TAPS_REQUIRED = 3

/**
 * 关于页（任务 5-3）：版本信息 + 开源许可声明。
 *
 * ExoPlayer（AndroidX Media3）为 Apache-2.0；FFmpeg 为 LGPL-2.1+ —— 本项目**不打包** FFmpeg
 * 二进制（由 ExoPlayer 的解码路径或系统 Codec 提供），但按 LGPL 要求在此声明组件与许可证出处。
 *
 * [onVersionTap] 是**唯一**的隐私模式入口：连点版本号 3 下触发。页面本身不出现任何
 * 「隐私」字样、按钮或图标 —— 这个参数由 `:app` 传入，本模块不知道隐私功能的存在
 * （模块图里也查不到依赖），比任何「隐藏字段」都彻底。
 */
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onVersionTap: () -> Unit = {},
) {
    // 连点判定：1.5s 内累计 3 次。不用 pointerInput 手写手势 —— 单击仍要能响应，
    // 用 clickable + 计数最简单，也不会和滚动抢事件（文本本身不滚动）。
    var taps by remember { mutableStateOf(0) }
    var windowStart by remember { mutableStateOf(0L) }

    Column(modifier.fillMaxSize()) {
        PageHeader(title = "关于", subtitle = "LimePlayer", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                // 底部 = 悬浮导航高度（已含手势条 + 呼吸）：这层 padding 在 verticalScroll
                // 之后，属于滚动内容的一部分 → 只在滚到底时留白，不会被导航胶囊压住
                .padding(bottom = LocalBottomNavSpace.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "LimePlayer",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "版本 $versionName · 为自建 NAS（Emby / Jellyfin / 飞牛影视）打造的 Android 播放器",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.text3,
                modifier = Modifier.clickable(
                    // 不给涟漪：有反馈就等于告诉旁人「这行能点」
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - windowStart > TAP_WINDOW_MS) {
                        taps = 0
                        windowStart = now
                    }
                    taps += 1
                    if (taps >= TAPS_REQUIRED) {
                        taps = 0
                        windowStart = 0
                        onVersionTap()
                    }
                },
            )

            HorizontalDivider()

            Text("开源组件与许可", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            LicenseBlock(
                name = "ExoPlayer / AndroidX Media3",
                license = "Apache License 2.0",
                note = "播放内核（HLS / MediaSession / 画中画）。版权归 Google LLC 及贡献者所有。",
            )
            LicenseBlock(
                name = "FFmpeg",
                license = "LGPL-2.1-or-later",
                note = "本应用不打包 FFmpeg 二进制；媒体解码经由 Android 系统 Codec 与 ExoPlayer 扩展路径。" +
                    "如后续引入 FFmpeg 扩展（libmpv / ffmpeg 扩展包），将按 LGPL 要求提供源码链接与重链接说明。",
            )
            LicenseBlock(
                name = "Kotlin / OkHttp / Coil / Compose",
                license = "Apache License 2.0",
                note = "语言、网络、图片加载与 UI 框架。",
            )

            HorizontalDivider()

            Text(
                "本应用为个人使用的开源项目；服务器凭据仅加密保存在本机（Android Keystore），" +
                    "不会上传到任何第三方服务。",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAppColors.current.text3,
            )
        }
    }
}

@Composable
private fun LicenseBlock(name: String, license: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            license,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = LocalAppColors.current.text3,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

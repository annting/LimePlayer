package app.myplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 入口 Activity。任务 1-0（骨架）阶段只验证「能装能启动」。
 *
 * 接下来会依次替换本文件的内容：
 * · 1-9  :core:designsystem 提供真实主题（颜色 / 字体 token，见 docs/03-UI设计规范.md §2）
 * · 1-12 接入导航（AppNavHost）与应用锁门卫（shouldLock）
 * · 1-13 起  由 :feature:server / :feature:library 接管页面
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SkeletonScreen() }
    }
}

@Composable
private fun SkeletonScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "LimePlayer", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "骨架就绪（任务 1-0）\n下一步：1-1 Ticks 与领域模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

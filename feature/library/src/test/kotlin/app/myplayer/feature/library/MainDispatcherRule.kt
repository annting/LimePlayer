package app.myplayer.feature.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `viewModelScope` 依赖 `Dispatchers.Main`：JVM 单测里用 TestDispatcher 顶替，否则直接崩。
 *
 * 原先这个类寄居在 `LibraryViewModelTest.kt` 里（同文件顺带写了个假仓库）。
 * 2026-11-02 删掉那个死掉的 ViewModel 及其测试时，`HomeViewModelTest` /
 * `HomeSettingsLinkTest` 仍在引用它 —— 所以它单独成文件：**测试基础设施不该跟着被测对象一起消失**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

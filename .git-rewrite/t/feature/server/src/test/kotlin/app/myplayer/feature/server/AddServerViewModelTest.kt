package app.myplayer.feature.server

import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AddServerViewModelTest {

    @Test fun `补全协议头并按需补默认端口`() {
        assertThat(normalizeBaseUrl("192.168.31.222", 8005)).isEqualTo("http://192.168.31.222:8005")
        assertThat(normalizeBaseUrl("http://nas:8096/", 8096)).isEqualTo("http://nas:8096")   // 已带端口 → 不重复追加
        assertThat(normalizeBaseUrl("https://nas", 443)).isEqualTo("https://nas")            // 默认端口 → 不追加
    }

    @Test fun `空地址归一化为空串`() {
        assertThat(normalizeBaseUrl("   ", 8096)).isEqualTo("")
    }

    @Test fun `探测成功回显服务器名并识别为飞牛协议`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ ->
            ProbeStatus.Reachable(serverName = "客厅 NAS", productName = "fnOS", version = "1.2.0800")
        })
        vm.onAddressChange("192.168.31.222")
        vm.onPortChange("8005")
        vm.onTest()
        val status = vm.state.value.probeStatus
        assertThat(status).isInstanceOf(ProbeStatus.Reachable::class.java)
        assertThat((status as ProbeStatus.Reachable).serverName).isEqualTo("客厅 NAS")
        assertThat(vm.state.value.detectedSourceType).isEqualTo(SourceType.JELLYFIN)
    }

    @Test fun `标准 Emby 识别为 EMBY`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ ->
            ProbeStatus.Reachable(serverName = "MyEmby", productName = "Emby Server", version = "4.8.11.0")
        })
        vm.onAddressChange("nas")
        vm.onPortChange("8096")
        vm.onTest()
        assertThat(vm.state.value.detectedSourceType).isEqualTo(SourceType.EMBY)
    }

    @Test fun `探测 404 时提示检查端口与路径前缀`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ -> ProbeStatus.NotFound })
        vm.onAddressChange("10.0.0.9")
        vm.onPortChange("8005")
        vm.onTest()
        assertThat(vm.state.value.error).contains("端口")
    }
}

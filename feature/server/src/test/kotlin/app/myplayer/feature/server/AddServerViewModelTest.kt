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

    @Test fun `默认选 Emby 且端口 8096`() {
        val vm = AddServerViewModel(probe = { _, _ -> ProbeStatus.Idle })
        assertThat(vm.state.value.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(vm.state.value.port).isEqualTo("8096")
    }

    @Test fun `切到飞牛影视默认端口变 5666 且探测按类型分派`() = runTest {
        var probedType: SourceType? = null
        val vm = AddServerViewModel(probe = { type, _ ->
            probedType = type
            ProbeStatus.Reachable(serverName = "飞牛影视", productName = "fnOS Media", version = "0.9.8")
        })
        vm.onSelectSource(SourceType.FNOS)
        assertThat(vm.state.value.sourceType).isEqualTo(SourceType.FNOS)
        assertThat(vm.state.value.port).isEqualTo("5666")

        vm.onAddressChange("192.168.31.222")
        vm.onTest()
        assertThat(probedType).isEqualTo(SourceType.FNOS)
        assertThat(vm.state.value.canProceed).isTrue()
    }

    @Test fun `手改过端口时切源不覆盖`() {
        val vm = AddServerViewModel(probe = { _, _ -> ProbeStatus.Idle })
        vm.onPortChange("8097")                 // 用户自建 Jellyfin 用了非默认端口
        vm.onSelectSource(SourceType.JELLYFIN)
        assertThat(vm.state.value.port).isEqualTo("8097")
    }

    @Test fun `切源会清掉上一次的探测结果`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ ->
            ProbeStatus.Reachable(serverName = "MyEmby", productName = "Emby Server", version = "4.8")
        })
        vm.onAddressChange("nas")
        vm.onTest()
        assertThat(vm.state.value.canProceed).isTrue()

        vm.onSelectSource(SourceType.FNOS)
        assertThat(vm.state.value.probeStatus).isEqualTo(ProbeStatus.Idle)
        assertThat(vm.state.value.canProceed).isFalse()
    }

    @Test fun `探测 404 时提示检查端口与路径前缀`() = runTest {
        val vm = AddServerViewModel(probe = { _, _ -> ProbeStatus.NotFound })
        vm.onAddressChange("10.0.0.9")
        vm.onPortChange("8005")
        vm.onTest()
        assertThat(vm.state.value.error).contains("端口")
    }

    @Test fun `展示名与默认端口按源区分`() {
        assertThat(sourceDisplayName(SourceType.EMBY)).isEqualTo("Emby")
        assertThat(sourceDisplayName(SourceType.JELLYFIN)).isEqualTo("Jellyfin")
        assertThat(sourceDisplayName(SourceType.FNOS)).isEqualTo("飞牛影视")
        assertThat(defaultPort(SourceType.FNOS)).isEqualTo(5666)
        assertThat(defaultPort(SourceType.EMBY)).isEqualTo(8096)
    }
}

package app.myplayer.feature.server

import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.model.SourceType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AddServerViewModelTest {

    private fun vm(
        kind: ServerKind = ServerKind.EMBY,
        probe: suspend (ServerKind, String) -> ProbeStatus = { _, _ -> ProbeStatus.Idle },
        auth: ServerAuthGateway = ServerAuthGateway { SignInResult.Ok },
        saveLocal: suspend (String) -> Unit = {},
        initial: ServerConfig? = null,
    ) = AddServerViewModel(
        probe = probe,
        auth = auth,
        saveLocal = saveLocal,
        initialKind = kind,
        initial = initial,
    )

    private val reachable: suspend (ServerKind, String) -> ProbeStatus =
        { _, _ -> ProbeStatus.Reachable("MyEmby", "Emby Server", "4.8") }

    private fun editing(
        baseUrl: String = "http://192.168.31.15:8096",
        type: SourceType = SourceType.EMBY,
        name: String = "客厅 Emby",
        probe: suspend (ServerKind, String) -> ProbeStatus = reachable,
    ) = vm(
        probe = probe,
        initial = ServerConfig(id = "s1", sourceType = type, name = name, baseUrl = baseUrl),
    )

    // ---- 默认值与类型切换 ----

    @Test fun `默认 Emby HTTP 8096`() {
        val s = vm().state.value
        assertThat(s.kind).isEqualTo(ServerKind.EMBY)
        assertThat(s.protocol).isEqualTo(NetProtocol.HTTP)
        assertThat(s.port).isEqualTo("8096")
        assertThat(s.networkFields)
            .containsExactly(NetworkField.Address, NetworkField.Port)
            .inOrder()
    }

    @Test fun `切到 SMB 端口变 445 且协议锁定`() {
        val v = vm()
        v.onSelectKind(ServerKind.SMB)
        val s = v.state.value
        assertThat(s.port).isEqualTo("445")
        assertThat(s.protocol).isEqualTo(NetProtocol.SMB)
        assertThat(s.kind.protocolLocked).isTrue()
        // SMB：地址 / 端口 / 域 / 共享目录；身份验证没有「认证方式」行
        assertThat(s.networkFields)
            .containsExactly(NetworkField.Address, NetworkField.Port, NetworkField.Domain, NetworkField.Share)
            .inOrder()
        assertThat(s.authFields)
            .containsExactly(AuthField.Username, AuthField.Password)
            .inOrder()
    }

    @Test fun `SMB 已接入：缺共享目录时不能提交`() {
        val v = vm()
        v.onSelectKind(ServerKind.SMB)
        v.onAddressChange("192.168.31.15")
        v.onUsernameChange("guest")
        val s = v.state.value
        // 2026-10-25 起 SMB 有真实源（:source:smb），不再是「未接入」的灰按钮
        assertThat(s.kind.isPlayable).isTrue()
        assertThat(s.canSubmit).isFalse()
        assertThat(s.submitBlockReason).contains("共享目录")
    }

    @Test fun `从 SMB 切回 Emby 清空域与目录并恢复端口`() {
        val v = vm()
        v.onSelectKind(ServerKind.SMB)
        v.onDomainChange("WORKGROUP")
        v.onShareChange("media")
        v.onSelectKind(ServerKind.EMBY)
        val s = v.state.value
        assertThat(s.port).isEqualTo("8096")
        assertThat(s.protocol).isEqualTo(NetProtocol.HTTP)
        assertThat(s.domain).isEmpty()
        assertThat(s.share).isEmpty()
    }

    @Test fun `同族内手改过端口时切类型不覆盖`() {
        val v = vm()
        v.onPortChange("8097")                 // 自建 Jellyfin 用了非默认端口
        v.onSelectKind(ServerKind.JELLYFIN)
        assertThat(v.state.value.port).isEqualTo("8097")
        // 跨到 SMB 则必须换默认值（语义不同，留着 8097 没意义）
        v.onSelectKind(ServerKind.SMB)
        assertThat(v.state.value.port).isEqualTo("445")
    }

    @Test fun `端口只接受数字且最多五位`() {
        val v = vm()
        v.onPortChange("80a96x")
        assertThat(v.state.value.port).isEqualTo("8096")
        v.onPortChange("123456")
        assertThat(v.state.value.port).isEqualTo("12345")
    }

    // ---- 地址粘贴 ----

    @Test fun `粘贴带协议头的地址会剥掉并纠偏协议`() {
        val v = vm()
        v.onAddressChange("https://nas.local")
        val s = v.state.value
        assertThat(s.address).isEqualTo("nas.local")
        assertThat(s.protocol).isEqualTo(NetProtocol.HTTPS)
        assertThat(s.targetUrl).isEqualTo("https://nas.local:8096")
    }

    @Test fun `粘贴 host 冒号 port 自动拆成两行`() {
        val v = vm()
        v.onAddressChange("192.168.31.222:8097")
        val s = v.state.value
        assertThat(s.address).isEqualTo("192.168.31.222")
        assertThat(s.port).isEqualTo("8097")
    }

    @Test fun `逐字输入不会把冒号抢走`() {
        val v = vm()
        v.onAddressChange("192.168.31.222")
        v.onAddressChange("192.168.31.222:")     // 只多了一个字符 = 手敲，不拆
        assertThat(v.state.value.address).isEqualTo("192.168.31.222:")
        assertThat(v.state.value.port).isEqualTo("8096")
    }

    // ---- URL 拼接与拆分 ----

    @Test fun `拼 URL 按协议与默认端口处理`() {
        assertThat(buildServerUrl(NetProtocol.HTTP, "192.168.31.15", "8096"))
            .isEqualTo("http://192.168.31.15:8096")
        assertThat(buildServerUrl(NetProtocol.HTTPS, "nas", "443"))
            .isEqualTo("https://nas")                                   // 默认端口不写
        assertThat(buildServerUrl(NetProtocol.HTTP, "nas", null))
            .isEqualTo("http://nas")                                    // 空端口不写
        assertThat(buildServerUrl(NetProtocol.SMB, "nas", "445", "/media"))
            .isEqualTo("smb://nas:445/media")
        assertThat(buildServerUrl(NetProtocol.HTTP, "http://nas", "8096"))
            .isEqualTo("http://nas:8096")                               // 粘进来的 scheme 被剥掉
    }

    @Test fun `拆 URL 四段都要对`() {
        assertThat(parseServerUrl("http://192.168.31.15:8096"))
            .isEqualTo(ParsedServerUrl(NetProtocol.HTTP, "192.168.31.15", "8096", null))
        assertThat(parseServerUrl("https://nas:8920"))
            .isEqualTo(ParsedServerUrl(NetProtocol.HTTPS, "nas", "8920", null))
        assertThat(parseServerUrl("http://nas:8096/emby"))
            .isEqualTo(ParsedServerUrl(NetProtocol.HTTP, "nas", "8096", "/emby"))
        assertThat(parseServerUrl("smb://nas:445/media"))
            .isEqualTo(ParsedServerUrl(NetProtocol.SMB, "nas", "445", "/media"))
        assertThat(parseServerUrl("nas"))
            .isEqualTo(ParsedServerUrl(null, "nas", null, null))
    }

    // ---- 认证方式 ----

    @Test fun `认证方式决定身份验证分组的行`() {
        val v = vm()
        assertThat(v.state.value.authFields)
            .containsExactly(AuthField.Mode, AuthField.Username, AuthField.Password)
            .inOrder()
        v.onAuthModeChange(AuthMode.NO_PASSWORD)
        assertThat(v.state.value.authFields)
            .containsExactly(AuthField.Mode, AuthField.Username)
            .inOrder()
        v.onAuthModeChange(AuthMode.API_KEY)
        assertThat(v.state.value.authFields)
            .containsExactly(AuthField.Mode, AuthField.ApiKey)
            .inOrder()
    }

    @Test fun `无密码模式只填账号就能提交`() = runTest {
        val v = vm(probe = reachable)
        v.onAuthModeChange(AuthMode.NO_PASSWORD)
        v.onAddressChange("192.168.31.15")
        v.onUsernameChange("ting.an2")
        v.onTest()
        assertThat(v.state.value.credentialsReady).isTrue()
        assertThat(v.state.value.canSubmit).isTrue()
    }

    @Test fun `API Key 模式缺 key 不能提交`() = runTest {
        val v = vm(probe = reachable)
        v.onAuthModeChange(AuthMode.API_KEY)
        v.onAddressChange("192.168.31.15")
        v.onTest()
        assertThat(v.state.value.canSubmit).isFalse()
        v.onApiKeyChange("abc123")
        assertThat(v.state.value.canSubmit).isTrue()
    }

    // ---- 探测 ----

    @Test fun `探测 404 提示检查端口与路径前缀`() = runTest {
        val v = vm(probe = { _, _ -> ProbeStatus.NotFound })
        v.onAddressChange("10.0.0.9")
        v.onTest()
        assertThat(v.state.value.error).contains("端口")
    }

    @Test fun `探测按类型分派并带上端口`() = runTest {
        var probedKind: ServerKind? = null
        var probedUrl: String? = null
        val v = vm(probe = { kind, url -> probedKind = kind; probedUrl = url; ProbeStatus.Reachable("x", null, null) })
        v.onSelectKind(ServerKind.FNOS)
        v.onAddressChange("192.168.31.222")
        v.onPortChange("5666")
        v.onTest()
        assertThat(probedKind).isEqualTo(ServerKind.FNOS)
        assertThat(probedUrl).isEqualTo("http://192.168.31.222:5666")
    }

    // ---- 编辑模式 ----

    @Test fun `编辑模式预填四段且只改名免探测`() {
        val s = editing().state.value
        assertThat(s.isEditing).isTrue()
        assertThat(s.kind).isEqualTo(ServerKind.EMBY)
        assertThat(s.protocol).isEqualTo(NetProtocol.HTTP)
        assertThat(s.address).isEqualTo("192.168.31.15")
        assertThat(s.port).isEqualTo("8096")
        assertThat(s.targetUrl).isEqualTo("http://192.168.31.15:8096")
        assertThat(s.endpointChanged).isFalse()
        assertThat(s.submitLabel).isEqualTo("保存")
        assertThat(s.canSubmit).isTrue()          // 未探测也能保存：端点没动
    }

    @Test fun `编辑模式保留 https 与路径前缀`() {
        val https = editing(baseUrl = "https://nas:8920").state.value
        assertThat(https.protocol).isEqualTo(NetProtocol.HTTPS)
        assertThat(https.targetUrl).isEqualTo("https://nas:8920")
        assertThat(https.endpointChanged).isFalse()

        val prefixed = editing(baseUrl = "http://nas:8096/emby").state.value
        assertThat(prefixed.address).isEqualTo("nas")
        assertThat(prefixed.path).isEqualTo("/emby")
        assertThat(prefixed.targetUrl).isEqualTo("http://nas:8096/emby")
        assertThat(prefixed.endpointChanged).isFalse()
    }

    @Test fun `编辑模式原地址没端口时端口框留空也不算改动`() {
        val s = editing(baseUrl = "http://nas").state.value
        assertThat(s.port).isEmpty()
        assertThat(s.targetUrl).isEqualTo("http://nas")
        assertThat(s.endpointChanged).isFalse()
    }

    @Test fun `编辑模式改地址后要有探测和凭据才能提交`() = runTest {
        val v = editing()
        v.onAddressChange("192.168.31.99")
        assertThat(v.state.value.endpointChanged).isTrue()
        assertThat(v.state.value.submitLabel).isEqualTo("登录")
        assertThat(v.state.value.canSubmit).isFalse()

        v.onTest()                                 // 地址通了，但编辑态没有已存密码
        assertThat(v.state.value.canSubmit).isFalse()
        v.onUsernameChange("ting.an2")
        v.onPasswordChange("2684")
        assertThat(v.state.value.canSubmit).isTrue()
    }

    @Test fun `编辑模式切类型也算端点改动`() {
        val v = editing()
        v.onSelectKind(ServerKind.JELLYFIN)
        assertThat(v.state.value.endpointChanged).isTrue()
        assertThat(v.state.value.canSubmit).isFalse()
    }

    // ---- 提交 ----

    @Test fun `新增提交走网关并置 Done`() = runTest {
        var captured: SignInRequest? = null
        val v = vm(
            probe = reachable,
            auth = ServerAuthGateway { req -> captured = req; SignInResult.Ok },
        )
        v.onAddressChange("192.168.31.15")
        v.onUsernameChange("ting.an2")
        v.onPasswordChange("2684")
        v.onTest()
        v.onSubmit()

        assertThat(captured?.baseUrl).isEqualTo("http://192.168.31.15:8096")
        assertThat(captured?.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(captured?.authMode).isEqualTo(AuthMode.PASSWORD)
        assertThat(captured?.activate).isTrue()
        assertThat(v.state.value.submitStatus).isEqualTo(SubmitStatus.Done)
    }

    @Test fun `登录失败回写错误并回到可重试状态`() = runTest {
        val v = vm(
            probe = reachable,
            auth = ServerAuthGateway { SignInResult.Failed("账号或密码错误") },
        )
        v.onAddressChange("192.168.31.15")
        v.onUsernameChange("ting.an2")
        v.onPasswordChange("bad")
        v.onTest()
        v.onSubmit()
        assertThat(v.state.value.submitStatus).isEqualTo(SubmitStatus.Idle)
        assertThat(v.state.value.error).isEqualTo("账号或密码错误")
    }

    @Test fun `编辑只改名走本地落盘不登录`() = runTest {
        var savedName: String? = null
        var loggedIn = false
        val v = vm(
            probe = reachable,
            auth = ServerAuthGateway { loggedIn = true; SignInResult.Ok },
            saveLocal = { savedName = it },
            initial = ServerConfig(id = "s1", sourceType = SourceType.EMBY, name = "客厅 Emby", baseUrl = "http://nas:8096"),
        )
        v.onNameChange("书房 Emby")
        v.onSubmit()
        assertThat(savedName).isEqualTo("书房 Emby")
        assertThat(loggedIn).isFalse()
        assertThat(v.state.value.submitStatus).isEqualTo(SubmitStatus.Done)
    }

    @Test fun `SMB 填全并测通后提交会走登录网关`() = runTest {
        var called = false
        val v = vm(
            probe = reachable,
            auth = ServerAuthGateway { called = true; SignInResult.Ok },
        )
        v.onSelectKind(ServerKind.SMB)
        v.onAddressChange("192.168.31.15")
        v.onShareChange("video")
        v.onUsernameChange("ting.an")
        v.onPasswordChange("secret")
        v.onTest()
        v.onSubmit()
        assertThat(called).isTrue()
    }

    // ---- 类型 ⇄ 源 的映射 ----

    @Test fun `ServerKind 与 SourceType 双向对得上且默认端口正确`() {
        assertThat(ServerKind.EMBY.sourceType).isEqualTo(SourceType.EMBY)
        assertThat(ServerKind.JELLYFIN.sourceType).isEqualTo(SourceType.JELLYFIN)
        assertThat(ServerKind.FNOS.sourceType).isEqualTo(SourceType.FNOS)
        assertThat(ServerKind.SMB.sourceType).isEqualTo(SourceType.SMB)
        assertThat(ServerKind.EMBY.defaultPort).isEqualTo(8096)
        assertThat(ServerKind.FNOS.defaultPort).isEqualTo(5666)
        assertThat(ServerKind.SMB.defaultPort).isEqualTo(445)
        ServerKind.entries.filter { it.sourceType != null }.forEach {
            assertThat(ServerKind.of(it.sourceType!!)).isEqualTo(it)
        }
    }
}

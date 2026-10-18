package app.myplayer.source.emby

import app.myplayer.core.model.ClientCapabilities

object DeviceProfile {

    /**
     * Emby 的 PlaybackInfo 需要 DeviceProfile，声明客户端能直放的能力。
     *
     * 容器列表的依据（**已实测**，勿凭直觉删减）：
     * - `mp4` / `webm`：Media3 原生渐进式支持（2026-10-17 真机验证直放成功）；
     * - `mkv`：Media3 Matroska 支持；
     * - `avi`：**Media3 1.11 内置 `AviExtractor`**（H.264 / MP4V / MJPEG 视频 + MP3 音频），
     *   且 NAS 存量资源大量是 AVI 封装（本机库里 4 个测试文件均为 H.264+MP3 的 avi）。
     *   Emby 对不声明 avi 的客户端会走「转码」路径，而其对 AVI 的 remux 在部分文件上会失败
     *   （`Error writing trailer ... Invalid data found`，2026-10-17 实测），故直放是更优路径。
     * - 不声明 `rmvb/wmv` 等 Media3 不支持的老封装：交给服务端转码（虽然可能也失败，但不给错的能力承诺）。
     */
    fun build(caps: ClientCapabilities): String {
        val video = caps.supportedVideoCodecs.joinToString(",")
        val audio = caps.supportedAudioCodecs.joinToString(",")
        return """
        {"DeviceProfile":{
          "MaxStreamingBitrate":120000000,
          "MaxStaticBitrate":120000000,
          "DirectPlayProfiles":[{"Container":"mp4,mkv,mov,webm,avi","Type":"Video","VideoCodec":"$video","AudioCodec":"$audio"}],
          "TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac","Protocol":"hls"}],
          "CodecProfiles":[],
          "SubtitleProfiles":[{"Format":"srt","Method":"External"},{"Format":"ass","Method":"External"}]
        }}""".trimIndent()
    }
}

package app.myplayer.source.emby

import app.myplayer.core.model.ClientCapabilities

object DeviceProfile {
    /** Emby 的 PlaybackInfo 需要 DeviceProfile，声明客户端能直放的能力。 */
    fun build(caps: ClientCapabilities): String {
        val video = caps.supportedVideoCodecs.joinToString(",")
        val audio = caps.supportedAudioCodecs.joinToString(",")
        return """
        {"DeviceProfile":{
          "MaxStreamingBitrate":120000000,
          "MaxStaticBitrate":120000000,
          "DirectPlayProfiles":[{"Container":"mp4,mkv,mov,webm","Type":"Video","VideoCodec":"$video","AudioCodec":"$audio"}],
          "TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac","Protocol":"hls"}],
          "CodecProfiles":[],
          "SubtitleProfiles":[{"Format":"srt","Method":"External"},{"Format":"ass","Method":"External"}]
        }}""".trimIndent()
    }
}

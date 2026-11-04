package com.mcarplay.app.video

/**
 * One Annex B H.264 access unit supplied by a non-MediaCodec source.
 *
 * [sps]/[pps] 是源解析器缓存的最近参数集（不含起始码，可能未内联在 [data] 里）：
 * 中途切到转码时，后续 IDR 不一定再带 PPS，解码器只能靠它们起步。
 */
data class ExternalVideoFrame(
    val data: ByteArray,
    val keyFrame: Boolean,
    val timestampUs: Long,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null,
)

/**
 * Control interface for a video producer that does not expose a Surface.
 *
 * [requestKeyFrame] is invoked when the CarPlay receiver asks for an
 * immediate IDR. Implementations may return false when the source has no
 * equivalent control channel.
 */
interface ExternalVideoSource {
    fun requestKeyFrame(): Boolean
}

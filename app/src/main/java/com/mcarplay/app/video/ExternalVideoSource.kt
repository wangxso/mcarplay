package com.mcarplay.app.video

/** One Annex B H.264 access unit supplied by a non-MediaCodec source. */
data class ExternalVideoFrame(
    val data: ByteArray,
    val keyFrame: Boolean,
    val timestampUs: Long,
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

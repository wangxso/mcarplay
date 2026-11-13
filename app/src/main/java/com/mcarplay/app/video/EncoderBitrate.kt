package com.mcarplay.app.video

/**
 * 编码码率估算（纯函数，单测钉死）。
 *
 * 旧实现内联在 `VideoSender.createEncoder`：`w*h*6` 限制在 2–10 Mbps，
 * 60fps 再 ×1.5，最终不超过 15 Mbps。固定 720p/1080p 后分辨率不再只由车机
 * 百分比决定，这里单独列出便于验证档位变化时的码率上下限。
 */
internal object EncoderBitrate {
    fun forSize(
        width: Int,
        height: Int,
        fps: Int,
    ): Int {
        val base = (width.toLong() * height * 6L).coerceIn(2_000_000L, 10_000_000L)
        val scaled = if (fps >= 60) base * 3 / 2 else base
        return scaled.coerceAtMost(15_000_000L).toInt()
    }
}

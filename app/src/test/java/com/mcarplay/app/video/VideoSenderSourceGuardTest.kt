package com.mcarplay.app.video

import com.mcarplay.app.architecture.SourceTree
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #78 复审 P1：CarWith 视频读取线程不得因网络写入被阻塞。
 *
 * 回归背景：`setVideoDimensions()` 由 CarWith 视频读取线程调用。若它去取 `lifecycleLock`，
 * 就会排到「转码发布者持有 lifecycleLock 等 `writeLock`」这条链后面；而 `writeLock` 可能正被
 * 阻塞的车机 socket 写占用（源码注释：满窗口时链路不发 RST，写会阻塞）。结果就是视频读取
 * 停摆——比画布尺寸错误严重得多。
 *
 * 这类约束无法用并发测试证明（阻塞点是真实 socket），所以在源码层钉住：
 * 该函数必须只依赖画布短锁，不得出现任何 `synchronized(...)`。
 */
class VideoSenderSourceGuardTest {
    private val sender = "video/VideoSender.kt"

    private fun bodyOf(
        code: String,
        signature: String,
    ): String {
        val start = code.indexOf(signature)
        assertTrue("源码中找不到 $signature", start >= 0)
        var index = code.indexOf('{', start + signature.length)
        assertTrue("$signature 没有函数体", index >= 0)
        var depth = 0
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(start, index + 1)
                }
            }
            index++
        }
        throw AssertionError("$signature 的函数体没有闭合")
    }

    @Test
    fun setVideoDimensionsNeverTakesALockThatCanWaitOnSocketWrites() {
        val code = SourceTree.scan(sender).code
        val body = bodyOf(code, "fun setVideoDimensions(")
        assertFalse(
            "setVideoDimensions() 不得取 synchronized 锁（会被阻塞的 socket 写拖住）：\n$body",
            body.contains("synchronized"),
        )
        // 必须仍然通过画布状态做原子更新，而不是直接写字段。
        assertTrue("应调用 canvas.applySourceSize(...)", body.contains("applySourceSize"))
    }

    /**
     * 反向保护：转码发布路径**必须**在发布画布时仍持有 lifecycleLock，
     * 否则与 stop() 的互斥会失效（stop 之后才初始化完的编码器由初始化方释放）。
     */
    @Test
    fun transcodePublicationStillHoldsTheLifecycleLock() {
        val code = SourceTree.scan(sender).code
        assertTrue("转码发布必须仍持有 lifecycleLock", code.contains("synchronized(lifecycleLock)"))
        assertTrue("转码发布必须在锁内发布画布", code.contains("publishEncodedCanvas"))
    }

    @Test
    fun encoderReportsSuccessfulFramesWithoutRequiringAnExternalSource() {
        val body = bodyOf(SourceTree.scan(sender).code, "private fun drainEncoder()")
        val write = body.indexOf("sendVideoFrame(")
        val notify = body.indexOf("if (sent > 0) runCatching { onVideoFrameSent?.invoke(sent) }")
        val externalOnlyStats = body.indexOf("if (sent > 0 && externalVideoSource != null)")
        assertTrue("编码帧成功写出后才回调，且不受外部源统计分支限制", write >= 0 && notify > write && externalOnlyStats > notify)
    }
}

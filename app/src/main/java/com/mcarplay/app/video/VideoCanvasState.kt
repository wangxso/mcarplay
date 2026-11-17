package com.mcarplay.app.video

/**
 * `VideoSender` 的画布状态：`width/height` 与「是否已切换转码」必须一起读写。
 *
 * 为什么需要单独收口（PR #78 复审 P2）：原先两条路径用不同的锁——
 *  - CarWith 源线程 `setVideoDimensions()` 判断 `isTranscoding` 后再写画布宽高；
 *  - 转码线程 `startTranscoding()` 在 lifecycleLock → writeLock 下同时发布
 *    `width/height/transcoder`。
 *
 * 于是存在这种交错：源线程读到「尚未转码」→ 转码线程发布固定画布 → 源线程再用 CarWith 的
 * SPS 尺寸把画布覆盖掉。此后 VideoConfig 快照虽然自身一致，却与编码器实际输出不符，
 * 实验会把「未生效」误报成「已生效」。
 *
 * 本类把「画布宽高」与「是否转码」放在同一个对象里，所有读写都经过它（内部同一把锁），
 * 判定与更新因此是原子的。纯函数 [decideSourceUpdate] / [decideCanvasUpdate] 让两条真实路径的
 * 交错可以被并发测试直接覆盖。
 */
internal class VideoCanvasState(
    initialWidth: Int,
    initialHeight: Int,
) {
    /** 一次画布读写的结果快照（宽高一定来自同一版本）。 */
    data class Size(
        val width: Int,
        val height: Int,
    )

    /** 源尺寸到达后是否应更新画布。[transcoding] 为 true 时画布归编码器所有。 */
    enum class SourceUpdate {
        /** 尚未转码：用 CarWith 源尺寸覆盖画布。 */
        APPLY,

        /** 已切换转码：画布固定为车机流尺寸，只记录源尺寸。 */
        KEEP_TRANSCODED,
    }

    /** 转码线程发布编码器画布后，是否应覆盖当前画布。 */
    enum class CanvasUpdate {
        /** 尚未转码：发布编码器画布。 */
        APPLY,

        /** 已经转码：画布已是编码器尺寸，不重复覆盖。 */
        KEEP,
    }

    /** 纯决策：源尺寸是否需要覆盖画布。 */
    fun decideSourceUpdate(transcoding: Boolean): SourceUpdate = if (transcoding) SourceUpdate.KEEP_TRANSCODED else SourceUpdate.APPLY

    /** 纯决策：转码发布是否需要覆盖画布。 */
    fun decideCanvasUpdate(transcoding: Boolean): CanvasUpdate = if (transcoding) CanvasUpdate.KEEP else CanvasUpdate.APPLY

    private val lock = Any()
    private var width = initialWidth
    private var height = initialHeight
    private var transcoding = false

    /** 发布转码画布，返回是否真的写入（false = 已经转码过，保持原样）。 */
    fun publishEncodedCanvas(
        width: Int,
        height: Int,
    ): Boolean =
        synchronized(lock) {
            val decision = decideCanvasUpdate(transcoding)
            transcoding = true
            if (decision != CanvasUpdate.APPLY) return false
            this.width = width
            this.height = height
            true
        }

    /**
     * CarWith 源尺寸更新。返回是否真的改了画布（false = 转码中画布固定）。
     * 判定与写入在同一把锁内，转码发布无法插进两者之间。
     */
    fun applySourceSize(
        width: Int,
        height: Int,
    ): Boolean =
        synchronized(lock) {
            val decision = decideSourceUpdate(transcoding)
            if (decision != SourceUpdate.APPLY) return false
            this.width = width
            this.height = height
            true
        }

    /** 一次读取宽高（两者来自同一版本）。 */
    fun read(): Size = synchronized(lock) { Size(width, height) }

    /** 宽高与「是否转码」的同一版本快照——诊断/测试用，避免分两次读出现读取间隙。 */
    fun readState(): Pair<Size, Boolean> = synchronized(lock) { Size(width, height) to transcoding }

    /** 无论是否转码都把画布还原为给定尺寸（会话收尾/重连复位用）。 */
    fun reset(
        width: Int,
        height: Int,
    ) {
        synchronized(lock) {
            this.width = width
            this.height = height
        }
    }

    fun isTranscoding(): Boolean = synchronized(lock) { transcoding }
}

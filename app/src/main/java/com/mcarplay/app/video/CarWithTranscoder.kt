package com.mcarplay.app.video

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarWith H.264 → 车机屏尺寸的手机端转码：硬解 → GL 拉伸 → 编码器输入 Surface。
 *
 * 帕萨特 2025（VW4211，屏 1280×720、maxFPS 40）收到 CarWith 原样透传的 1920×1140 流后，
 * 每 ~300ms 要一次关键帧、始终不出画（2026-10-31 23:52/23:59 两轮）。CarWith 不采纳请求尺寸，
 * 也不响应「要关键帧」，只能由手机把画面缩到车机声明的尺寸再编码。
 *
 * 拉伸铺满而非加黑边：触摸按「车机屏 → CarWith 画面」等比映射（mapCarLifeTouch），
 * 两边都铺满时点击位置才对得上；1.68 → 1.78 的横向拉伸约 5%。
 */
internal class CarWithTranscoder(
    private val encoderSurface: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val log: (String) -> Unit,
    /** 向 CarWith 要关键帧（解码输入断流后用；转码线程上调用，已限流）。 */
    private val requestSourceKeyFrame: () -> Unit = {},
) {
    private val running = AtomicBoolean(true)
    private val glThread = HandlerThread("carwith-transcode-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    @Volatile
    private var decoder: MediaCodec? = null
    private var outputThread: Thread? = null

    /** 解码器发布与 [stop] 互斥：stop 之后才启动完的解码器由创建方自己释放，不再发布。 */
    private val decoderLock = Any()

    @Volatile
    var framesRendered = 0L
        private set
    private var inputDropped = 0L
    private val resync = IdrResync()
    private var framesSkippedForIdr = 0L

    @Volatile
    private var framesSubmitted = 0L

    @Volatile
    private var framesDecoded = 0L

    @Volatile
    private var decoderFormat: String? = null

    /**
     * 单调递增的解码输入 / 编码展示时间。CarLife 时间戳以秒计（同秒几十帧同值），
     * 直接沿用会让编码器把时间戳不递增的帧丢掉（2026-11-01 审查）。
     */
    private var lastInputPtsUs = 0L
    private var lastPresentationNs = 0L

    /** 在 GL 线程上建 EGL（输出到编码器 Surface）与外部纹理；失败抛出，由调用方回退透传。 */
    fun start() {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        glHandler.post {
            try {
                setUpGl()
            } catch (error: Throwable) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(3, TimeUnit.SECONDS)) { "GL 初始化超时" }
        failure?.let { throw it }
        log("VIDEO: 转码管线就绪 ${sourceWidth}x$sourceHeight → ${outWidth}x$outHeight（等 CarWith 关键帧起解码）")
    }

    /**
     * 喂一个 CarWith 访问单元（Annex B）。解码器在首个关键帧到来时创建，参数集优先取帧内联的，
     * 缺的从解析器缓存补：CarLifeJoinGate 只在首次接入时补齐，中途切换后的 IDR 可能不带 PPS。
     */
    fun submit(frame: ExternalVideoFrame) {
        if (!running.get()) return
        framesSubmitted++
        val codec =
            decoder ?: run {
                if (!frame.keyFrame) return
                val sets = decoderParameterSets(frame) ?: return
                startDecoder(sets.first, sets.second) ?: return
            }
        if (!resync.admit(frame.keyFrame)) {
            framesSkippedForIdr++
            requestSourceKeyFrameIfDue()
            return
        }
        try {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                inputDropped++
                if (inputDropped == 1L || inputDropped % 100 == 0L) {
                    log("VIDEO: 转码解码器输入繁忙，已丢 $inputDropped 帧（等 CarWith 下一个 IDR 再续）")
                }
                dropped()
                return
            }
            val buffer =
                codec.getInputBuffer(index) ?: run {
                    dropped()
                    return
                }
            buffer.clear()
            if (frame.data.size > buffer.capacity()) {
                codec.queueInputBuffer(index, 0, 0, frame.timestampUs, 0)
                log("VIDEO: 转码输入帧 ${frame.data.size}B 超出解码缓冲 ${buffer.capacity()}B，丢弃")
                dropped()
                return
            }
            buffer.put(frame.data)
            lastInputPtsUs = maxOf(lastInputPtsUs + 1, System.nanoTime() / 1_000L)
            codec.queueInputBuffer(index, 0, frame.data.size, lastInputPtsUs, 0)
        } catch (error: Exception) {
            if (running.get()) log("VIDEO: 转码解码输入异常 - ${error.message}")
            dropped()
        }
    }

    /** 丢了一个访问单元：后续 P 帧缺参考，等 IDR 并向 CarWith 要关键帧。 */
    private fun dropped() {
        resync.discontinuity()
        requestSourceKeyFrameIfDue()
    }

    private fun requestSourceKeyFrameIfDue() {
        if (!resync.requestDue(android.os.SystemClock.uptimeMillis())) return
        runCatching { requestSourceKeyFrame() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val codec: MediaCodec?
        val thread: Thread?
        synchronized(decoderLock) {
            codec = decoder
            thread = outputThread
            decoder = null
            outputThread = null
        }
        thread?.let {
            it.interrupt()
            runCatching { it.join(500) }
        }
        codec?.let(::releaseCodec)
        val released = CountDownLatch(1)
        glHandler.post {
            releaseGl()
            released.countDown()
        }
        runCatching { released.await(1, TimeUnit.SECONDS) }
        glThread.quitSafely()
        log("VIDEO: 转码管线已停止（共渲染 $framesRendered 帧，输入丢弃 $inputDropped 帧，等 IDR 跳过 $framesSkippedForIdr 帧）")
    }

    private fun startDecoder(
        sps: ByteArray,
        pps: ByteArray,
    ): MediaCodec? {
        val surface = decoderSurface ?: return null
        var created: MediaCodec? = null
        return try {
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sourceWidth, sourceHeight).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created = codec
            codec.configure(format, surface, null, 0)
            codec.start()
            val published =
                synchronized(decoderLock) {
                    if (!running.get()) return@synchronized false
                    decoder = codec
                    outputThread =
                        Thread({ drainDecoder(codec) }, "carwith-transcode-decode").apply {
                            isDaemon = true
                            start()
                        }
                    true
                }
            if (!published) {
                releaseCodec(codec)
                return null
            }
            log("VIDEO: 转码解码器已启动 ${sourceWidth}x$sourceHeight（${codec.name}）")
            codec
        } catch (error: Exception) {
            if (running.get()) log("VIDEO: 转码解码器启动失败 - ${error.message}")
            created?.takeIf { it !== decoder }?.let(::releaseCodec)
            null
        }
    }

    /** 一行转码管线统计（诊断）：CarWith 输入 → 解码 → 渲染进编码器。 */
    fun statsLine(): String =
        "输入 $framesSubmitted 帧 → 解码 $framesDecoded → 渲染 $framesRendered；输入丢弃 $inputDropped、" +
            "等 IDR 跳过 $framesSkippedForIdr；解码器${if (decoder == null) "未启动" else "运行中"}"

    private fun releaseCodec(codec: MediaCodec) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /** 解码输出直接渲染到 SurfaceTexture；GL 线程在 onFrameAvailable 里缩放写进编码器。 */
    private fun drainDecoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index =
                try {
                    codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                } catch (error: Exception) {
                    if (running.get()) log("VIDEO: 转码解码输出异常，停止 - ${error.message}")
                    return
                }
            if (index >= 0) {
                if (info.size > 0) framesDecoded++
                runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                decoderFormat = runCatching { codec.outputFormat.toString() }.getOrNull()
                log("VIDEO: 转码解码器输出格式 $decoderFormat")
            }
        }
    }

    private fun setUpGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
        check(EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)) { "eglInitialize" }
        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        eglContext =
            EGL14.eglCreateContext(
                eglDisplay,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        program = buildProgram()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texture = SurfaceTexture(textureId)
        texture.setDefaultBufferSize(sourceWidth, sourceHeight)
        texture.setOnFrameAvailableListener({ drawFrame() }, glHandler)
        surfaceTexture = texture
        decoderSurface = Surface(texture)
    }

    private fun drawFrame() {
        val texture = surfaceTexture ?: return
        if (!running.get()) return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coord = GLES20.glGetAttribLocation(program, "aTexCoord")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(coord)
            GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, TEX)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            lastPresentationNs = maxOf(lastPresentationNs + 1, System.nanoTime())
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, lastPresentationNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            framesRendered++
            if (framesRendered == 1L) log("VIDEO: ✓ 转码首帧已写入编码器（${outWidth}x$outHeight）")
        } catch (error: Exception) {
            log("VIDEO: 转码渲染异常 - ${error.message}")
        }
    }

    private fun releaseGl() {
        runCatching { decoderSurface?.release() }
        decoderSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(): Int {
        fun compile(
            type: Int,
            source: String,
        ): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(id, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "program 链接失败: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    companion object {
        /** EGL_RECORDABLE_ANDROID：EGL 窗口面要喂给 MediaCodec 编码器时必须声明。 */
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val INPUT_TIMEOUT_US = 20_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L

        /** CarWith 1920×1140 关键帧实测最大约 370KB，留足余量。 */
        private const val MAX_INPUT_SIZE = 1_048_576
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        private const val VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                "attribute vec4 aTexCoord;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aPosition;\n" +
                "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
                "}\n"

        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTexCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                "}\n"

        private val QUAD: FloatBuffer = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX: FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        /** 从 Annex B 访问单元里取 SPS/PPS（不含起始码）；缺任一返回 null。 */
        internal fun parameterSets(annexB: ByteArray): Pair<ByteArray, ByteArray>? {
            val (sps, pps) = inlineParameterSets(annexB)
            return if (sps != null && pps != null) sps to pps else null
        }

        /** 解码器起步用的参数集：内联优先，缺的取帧携带的解析器缓存；仍缺任一返回 null。 */
        internal fun decoderParameterSets(frame: ExternalVideoFrame): Pair<ByteArray, ByteArray>? {
            val (inlineSps, inlinePps) = inlineParameterSets(frame.data)
            val sps = inlineSps ?: frame.sps?.takeIf { it.isNotEmpty() }
            val pps = inlinePps ?: frame.pps?.takeIf { it.isNotEmpty() }
            return if (sps != null && pps != null) sps to pps else null
        }

        private fun inlineParameterSets(annexB: ByteArray): Pair<ByteArray?, ByteArray?> {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in AnnexB.split(annexB)) {
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1F) {
                    7 -> if (sps == null) sps = nal
                    8 -> if (pps == null) pps = nal
                }
            }
            return sps to pps
        }
    }
}

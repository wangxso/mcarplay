package com.mcarplay.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * 车机壁纸存储：当前壁纸 = 用户选择的图片（files/wallpaper.jpg）或内置默认
 * （assets/wallpaper_default.jpg，Apple CarPlay 官方深色壁纸压缩版）。
 *
 * 合成器以位图上传 GL 纹理；更换壁纸后经 [version] 递增通知重载。
 */
object WallpaperStore {
    /** 内置默认壁纸（assets 内文件名）。 */
    const val DEFAULT_ASSET = "wallpaper_default.jpg"

    private const val USER_FILE = "wallpaper.jpg"

    private val versionCounter =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** 当前壁纸版本（更换后递增；接收方据此判断是否需要重载）。 */
    val version: Long get() = versionCounter.get()

    // ---- 变更通知（监听器模式；接收方注册后应立即读一次当前状态兜底） ----

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Long) -> Unit>()

    /** 注册壁纸变更监听（在主线程回调；参数为新版本号）。 */
    fun addListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    fun markChanged() {
        val version = versionCounter.incrementAndGet()
        listeners.forEach { runCatching { it(version) } }
    }

    /** 用户壁纸文件（null = 使用内置默认）。 */
    fun userWallpaperFile(context: Context): File? = File(context.filesDir, USER_FILE).takeIf { it.exists() && it.length() > 0 }

    /**
     * 解码当前壁纸（GL 线程调用；失败回退内置默认，再失败回退 null 由合成器画纯色）。
     * 按 [maxSize] 限制边长（显存友好；aspectFill 由合成器按比例缩放绘制）。
     */
    fun decode(
        context: Context,
        maxSize: Int = 2560,
    ): Bitmap? {
        decodeUser(context, maxSize)?.let { return it }
        return decodeDefault(context, maxSize)
    }

    private fun decodeUser(
        context: Context,
        maxSize: Int,
    ): Bitmap? {
        val file = userWallpaperFile(context) ?: return null
        return runCatching {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (info.size.width > maxSize || info.size.height > maxSize) {
                    decoder.setTargetSampleSize(
                        maxOf(info.size.width, info.size.height) / maxSize + 1,
                    )
                }
            }
        }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun decodeDefault(
        context: Context,
        maxSize: Int,
    ): Bitmap? =
        runCatching {
            context.assets.open(DEFAULT_ASSET).use { input ->
                val options =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeStream(input, null, this)
                    }
                context.assets.open(DEFAULT_ASSET).use { stream ->
                    options.inJustDecodeBounds = false
                    options.inSampleSize = sampleSize(options.outWidth, options.outHeight, maxSize)
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        }.getOrNull()

    /** 复制用户选择的图片到内部存储并标记变更（重复选择同一图也覆盖，幂等）。 */
    fun setFromUri(
        context: Context,
        uri: Uri,
    ): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(context.filesDir, USER_FILE).outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            markChanged()
            true
        }.getOrDefault(false)

    /** 恢复内置默认壁纸。 */
    fun resetToDefault(context: Context): Boolean =
        runCatching {
            File(context.filesDir, USER_FILE).delete()
            markChanged()
            true
        }.getOrDefault(false)

    private fun sampleSize(
        width: Int,
        height: Int,
        maxSize: Int,
    ): Int {
        var sample = 1
        while (width / sample > maxSize || height / sample > maxSize) sample *= 2
        return sample
    }

    // ---- 桌面切片：全流画布的 aspectFill 位图（GridActivity/DockActivity 共用） ----

    /** 双尺寸缓存（桌面 = 超采样屏尺寸、dock = 流尺寸各一份）。 */
    private val canvasCache = HashMap<String, Bitmap>()
    private var canvasCacheVersion = -1L

    /** 画布位图就绪监听（首次构建/版本更换重建后回调；参数 = 画布版本号）。 */
    private val canvasReadyListeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    fun addCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.add(listener)
    }

    fun removeCanvasReadyListener(listener: (Long) -> Unit) {
        canvasReadyListeners.remove(listener)
    }

    /**
     * 按「整个视频流画布」做 aspectFill 的壁纸位图（调用方把切片偏移到自己的窗口位置）。
     * 按尺寸+版本缓存（桌面与 dock 各一份）；后台线程调用，返回的位图只读。
     */
    @Synchronized
    fun aspectFillCanvas(
        context: Context,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val key = "${canvasWidth}x$canvasHeight"
        canvasCache[key]?.let { cached ->
            if (canvasCacheVersion == version) return cached
        }
        if (canvasCacheVersion != version) canvasCache.clear()
        val source = decode(context, maxOf(canvasWidth, canvasHeight) * 2) ?: return null
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val scale =
            maxOf(
                canvasWidth.toFloat() / source.width,
                canvasHeight.toFloat() / source.height,
            )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        // 必须用「目标矩形」重载缩放绘制——drawBitmap(bmp, x, y, paint) 按原图像素
        // 尺寸绘制：源图小于画布时（如竖屏照片 < 横屏画布宽）右侧留空（透明黑）
        val dest =
            android.graphics.Rect(
                ((canvasWidth - drawWidth) / 2f).toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt(),
                ((canvasWidth - drawWidth) / 2f).toInt() + drawWidth.toInt(),
                ((canvasHeight - drawHeight) / 2f).toInt() + drawHeight.toInt(),
            )
        canvas.drawBitmap(source, null, dest, paint)
        canvasCacheVersion = version
        canvasCache[key] = bitmap
        canvasReadyListeners.forEach { runCatching { it(version) } }
        return bitmap
    }
}

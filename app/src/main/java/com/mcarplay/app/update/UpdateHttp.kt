package com.mcarplay.app.update

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class UpdateJob {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    fun check() {
        if (cancelled.get()) throw UpdateException("CANCELLED", "更新操作已取消")
    }
}

object UpdateJobs {
    private var active: UpdateJob? = null

    @Synchronized fun acquire(): UpdateJob? = if (active != null) null else UpdateJob().also { active = it }

    @Synchronized fun release(job: UpdateJob) {
        if (active === job) active = null
    }
}

class UpdateResponse(
    val input: InputStream,
    private val cleanup: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            input.close()
        } finally {
            cleanup()
        }
    }
}

fun interface UpdateTransport {
    fun open(
        url: String,
        job: UpdateJob,
    ): UpdateResponse
}

object GitHubHttp : UpdateTransport {
    override fun open(
        url: String,
        job: UpdateJob,
    ): UpdateResponse {
        var current = url
        repeat(6) {
            job.check()
            UpdateSource.requireTransportUrl(current)
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "mcarplay-updater")
                connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                job.check()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location =
                        connection.getHeaderField("Location")
                            ?: throw UpdateException("BAD_REDIRECT", "GitHub 返回了无效的下载跳转")
                    current = URI(current).resolve(location).toString()
                    connection.disconnect()
                } else {
                    if (status == 403 || status == 429) throw UpdateException("RATE_LIMIT", "GitHub 请求受限，请稍后重试或打开发行页")
                    if (status != 200) throw UpdateException("HTTP_$status", "GitHub 请求失败（HTTP $status），请稍后重试")
                    return UpdateResponse(connection.inputStream) { connection.disconnect() }
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw UpdateException("REDIRECT_LIMIT", "GitHub 下载跳转次数过多，已停止")
    }
}

fun UpdateTransport.text(
    url: String,
    limit: Int,
    job: UpdateJob,
): String =
    open(url, job).use { response ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            job.check()
            val count = response.input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw UpdateException("RESPONSE_TOO_LARGE", "GitHub 返回内容过大，已停止")
            output.write(buffer, 0, count)
        }
        job.check()
        output.toString(Charsets.UTF_8.name())
    }

package com.mcarplay.app.bridge

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 块差分补丁应用（与服务端 deltadiff.ts 的二进制格式硬约定，勿单独改动）：
 *
 *   "CDDELTA1"  8B magic
 *   targetLen   u64 LE
 *   targetSha256 32B
 *   baseLen     u64 LE
 *   baseSha256  32B
 *   ops: 0x01 COPY(u64 oldOffset,u64 len) | 0x02 LITERAL(u64 len + bytes)
 *
 * 安全性不依赖补丁本身可信：应用后强制校验产物 sha256，不符即整包作废走
 * 全量回退。基线固定用本进程自己的 sourceDir（唯一能读到且与服务器基线
 * 逐字节一致的来源）。
 */
object DeltaPatch {
    private const val MAGIC = "CDDELTA1"

    /**
     * 把 [patch] 应用到 [base]（必须与服务器差分基线逐字节一致）写出 [out]。
     * 返回 null = 成功；否则返回失败原因（调用方回退全量下载）。
     */
    fun apply(
        patch: File,
        base: File,
        out: File,
    ): String? {
        if (!base.isFile) return "基线 APK 不存在"
        val sha = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(patch, "r").use { pf ->
            val magic = ByteArray(8)
            pf.readFully(magic)
            if (!String(magic, Charsets.US_ASCII).startsWith(MAGIC)) return "补丁格式不对"
            val targetLen = pf.readLong()
            val targetSha = ByteArray(32).also { pf.readFully(it) }
            val baseLen = pf.readLong()
            val baseSha = ByteArray(32).also { pf.readFully(it) }
            if (base.length() != baseLen) return "基线长度不符（${base.length()}/$baseLen）"
            if (!sha256Of(base).contentEquals(baseSha)) return "基线 sha256 不符"
            RandomAccessFile(base, "r").use { bf ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        when (pf.readByte().toInt()) {
                            0x01 -> {
                                // COPY：从基线 seek+复制
                                val off = pf.readLong()
                                var len = pf.readLong()
                                bf.seek(off)
                                while (len > 0) {
                                    val n = bf.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
                                    if (n < 0) return "基线读取越界"
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                    written += n
                                    len -= n
                                }
                            }

                            0x02 -> {
                                // LITERAL：补丁内联字节
                                var len = pf.readLong()
                                while (len > 0) {
                                    val n = pf.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
                                    if (n < 0) return "补丁读取越界"
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                    written += n
                                    len -= n
                                }
                            }

                            else -> return "未知指令"
                        }
                        if (written > targetLen) return "产物超长"
                        if (written == targetLen) break
                    }
                    if (!sha.digest().contentEquals(targetSha)) return "产物 sha256 不符（补丁与基线可能不配套）"
                    return null
                }
            }
        }
    }

    internal fun sha256Of(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}

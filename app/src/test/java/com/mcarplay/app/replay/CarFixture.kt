package com.mcarplay.app.replay

import org.json.JSONObject
import java.io.File

/**
 * 车型重放 fixture 加载器（JSONL）。目录默认 gitignore——新鲜克隆无 fixture 时
 * 测试自动跳过（见各 Replay 测试的目录扫描），本地提取后即为回归闸门。
 */
class CarFixture(
    val name: String,
    val car: String,
    val source: String,
) {
    class BtRound(
        val index: Int,
        val outcome: String,
        val durationMs: Long,
        val rx: ByteArray,
        val recordedTx: ByteArray,
        /** RAW 预算截断过尾部残帧（已在提取时裁齐）。 */
        val rxTruncated: Boolean,
        /** 完整识别帧（0x1D01）幸存——true 才能做识别阶段的全量断言。 */
        val rxHasIdent: Boolean,
    )

    val btRounds = ArrayList<BtRound>()
    val tunnelRx = ArrayList<Pair<Int, ByteArray>>() // 消息号 → CSM 体
    val tunnelTxIds = ArrayList<Int>()

    companion object {
        fun loadDirectory(): List<CarFixture> {
            // src/test/resources 在 Gradle 测试运行时是 classpath；从位置反推源码目录
            // （动态 fixture 不打进 jar，按文件系统读最直接）
            val roots =
                listOf(
                    File("src/test/resources/carplay/fixtures"),
                    File("app/src/test/resources/carplay/fixtures"),
                )
            val dir = roots.firstOrNull { it.isDirectory } ?: return emptyList()
            return dir
                .listFiles { f -> f.extension == "jsonl" }
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file ->
                    runCatching { parse(file) }.getOrElse {
                        println("REPLAY: fixture 解析失败 ${file.name}: $it")
                        null
                    }
                }
        }

        private fun parse(file: File): CarFixture {
            val lines = file.readLines().filter { it.isNotBlank() }
            val meta = JSONObject(lines.first())
            val fixture =
                CarFixture(
                    name = file.nameWithoutExtension,
                    car = meta.getJSONObject("meta").optString("car", "unknown"),
                    source = meta.getJSONObject("meta").optString("source", file.name),
                )
            lines.drop(1).forEach { line ->
                val obj = JSONObject(line)
                when (obj.optString("layer")) {
                    "bt" ->
                        fixture.btRounds.add(
                            BtRound(
                                index = obj.getInt("round"),
                                outcome = obj.optString("outcome", "identified"),
                                durationMs = obj.optLong("durationMs", 0),
                                rx = hex(obj.getString("rxHex")),
                                recordedTx = hex(obj.optString("txHex", "")),
                                rxTruncated = obj.optBoolean("rxTruncated", false),
                                rxHasIdent = obj.optBoolean("rxHasIdent", false),
                            ),
                        )

                    "tunnel" ->
                        if (obj.optString("dir") == "rx") {
                            fixture.tunnelRx.add(obj.getInt("id") to hex(obj.getString("hex")))
                        } else {
                            fixture.tunnelTxIds.add(obj.getInt("id"))
                        }
                }
            }
            return fixture
        }

        private fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { i ->
                text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
    }
}

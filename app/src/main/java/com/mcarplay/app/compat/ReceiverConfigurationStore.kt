package com.mcarplay.app.compat

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 有界、版本化的私有存储格式；只保存本机摘要与白名单参数，不含地址、凭据或配对材料。 */
data class ReceiverConfigurationStore(
    val secret: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
    val entries: List<LocalReceiverConfiguration> = emptyList(),
) {
    fun binding(address: String?): String? {
        if (address == null || !ADDRESS.matches(address) || address.equals("02:00:00:00:00:00", true)) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(address.uppercase(Locale.ROOT).toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    fun find(
        binding: String,
        environment: String,
    ): LocalReceiverConfiguration? = entries.firstOrNull { it.binding == binding && it.environment == environment }

    fun with(configuration: LocalReceiverConfiguration): ReceiverConfigurationStore =
        copy(entries = (listOf(configuration) + entries.filter { it.binding != configuration.binding }).take(MAX_ENTRIES))

    fun without(binding: String): ReceiverConfigurationStore = copy(entries = entries.filter { it.binding != binding })

    fun encode(): String =
        JSONObject()
            .put("schema", SCHEMA)
            .put("engine", ENGINE)
            .put("secret", Base64.getEncoder().encodeToString(secret))
            .put(
                "entries",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(
                            JSONObject()
                                .put("binding", entry.binding)
                                .put("environment", entry.environment)
                                .put("capabilities", entry.capabilities)
                                .put("savedAtMs", entry.savedAtMs)
                                .put("build", entry.build)
                                .put("bluetooth", entry.bluetooth.name)
                                .put("infoRequest", entry.infoRequest.name)
                                .put("audioProfile", entry.audioProfile ?: JSONObject.NULL)
                                .put("video", entry.verification.video.name)
                                .put("audio", entry.verification.audio.name)
                                .put("touch", entry.verification.touch.name)
                                .put("observedSeconds", entry.observedSeconds),
                        )
                    }
                },
            ).toString()

    companion object {
        const val SCHEMA = 1
        const val ENGINE = 1
        const val MAX_ENTRIES = 32
        const val MAX_BYTES = 128_000
        private val ADDRESS = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
        private val DIGEST = Regex("[a-f0-9]{64}")
        private val AUDIO = Regex("[A-Za-z0-9_/.:+|\\-]{1,100}")

        fun decode(text: String): ReceiverConfigurationStore {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JSONObject(text)
            require(root.getInt("schema") == SCHEMA && root.getInt("engine") == ENGINE)
            val secret = Base64.getDecoder().decode(root.getString("secret"))
            require(secret.size == 32)
            val array = root.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES)
            val entries =
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val binding = item.getString("binding").also { require(DIGEST.matches(it)) }
                    val environment = item.getString("environment").also { require(DIGEST.matches(it)) }
                    val capabilities = item.getString("capabilities").also { require(DIGEST.matches(it)) }
                    val audioProfile = if (item.isNull("audioProfile")) null else item.getString("audioProfile")
                    require(audioProfile == null || AUDIO.matches(audioProfile))
                    val verification =
                        ReceiverVerification(
                            ReceiverCheck.valueOf(item.getString("video")),
                            ReceiverCheck.valueOf(item.getString("audio")),
                            ReceiverCheck.valueOf(item.getString("touch")),
                        )
                    require(verification.video == ReceiverCheck.CONFIRMED)
                    require(audioProfile == null || verification.audio == ReceiverCheck.CONFIRMED)
                    LocalReceiverConfiguration(
                        binding,
                        environment,
                        capabilities,
                        item.getLong("savedAtMs").also { require(it >= 0) },
                        item.getString("build").also { require(it.length <= 100) },
                        BluetoothBringup.valueOf(item.getString("bluetooth")),
                        InfoRequest.valueOf(item.getString("infoRequest")),
                        audioProfile,
                        verification,
                        item.getLong("observedSeconds").also { require(it >= 0) },
                    )
                }
            require(entries.map { it.binding }.distinct().size == entries.size)
            return ReceiverConfigurationStore(secret, entries)
        }
    }
}

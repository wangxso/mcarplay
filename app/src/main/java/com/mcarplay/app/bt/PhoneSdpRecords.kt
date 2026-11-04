package com.mcarplay.app.bt

import java.io.Closeable
import java.util.UUID

/** 手机的传输服务与 CarPlay 能力公告必须一起注册、一起撤销。 */
internal class PhoneSdpRecords<T : Closeable> private constructor(
    val iap2: T,
    val carPlay: T,
) : Closeable {
    override fun close() {
        runCatching { carPlay.close() }
        runCatching { iap2.close() }
    }

    companion object {
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        val IPHONE_CARPLAY_UUID: UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")

        fun <T : Closeable> open(register: (String, UUID) -> T): PhoneSdpRecords<T> {
            val iap2 = register("iAP2", IPHONE_IAP2_UUID)
            try {
                return PhoneSdpRecords(iap2, register("CarPlay", IPHONE_CARPLAY_UUID))
            } catch (error: Exception) {
                runCatching { iap2.close() }
                throw error
            }
        }
    }
}

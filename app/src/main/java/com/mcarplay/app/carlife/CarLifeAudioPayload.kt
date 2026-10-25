package com.mcarplay.app.carlife

/** Explicit package-specific protection; failed decryption never falls back to plaintext. */
object CarLifeAudioPayload {
    enum class Mode { ENCRYPTED, XIAOMI_MIX }

    fun decode(
        frame: CarLifeFrame,
        cipher: CarLifePayloadCipher?,
        mode: Mode,
    ): ByteArray {
        // Xiaomi's PcmHoldHelper encrypts INIT via PCMPackageHead, but copies
        // MEDIA_DATA samples directly into the socket buffer without AES.
        val rawMixPcm =
            mode == Mode.XIAOMI_MIX &&
                frame.channel == CarLifeProtocol.PORT_MEDIA &&
                frame.kind == CarLifeProtocol.MEDIA_DATA
        return if (cipher == null || frame.payload.isEmpty() || rawMixPcm) frame.payload else cipher.decrypt(frame.payload)
    }
}

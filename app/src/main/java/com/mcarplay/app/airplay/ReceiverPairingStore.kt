package com.mcarplay.app.airplay

import android.content.Context
import com.mcarplay.app.iap2.Iap2SenderSession
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal class ReceiverPairingStore(
    private val context: Context?,
) {
    fun load(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        controllerId: String,
    ): PairSetupClient.ReceiverIdentity? {
        val key = key(endpoint, controllerId) ?: return null
        val value = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(key, null) ?: return null
        return runCatching {
            val stored = JSONObject(value)
            val publicKey = Base64.getDecoder().decode(stored.getString("publicKey"))
            val receiverId = stored.getString("pairingId")
            if (publicKey.size != 32 || receiverId.isBlank()) return null
            PairSetupClient.ReceiverIdentity(receiverId, publicKey)
        }.getOrNull()
    }

    fun save(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        controllerId: String,
        receiver: PairSetupClient.ReceiverIdentity,
    ) {
        val key = key(endpoint, controllerId) ?: return
        if (receiver.publicKey.size != 32 || receiver.pairingId.isBlank()) return
        val value =
            JSONObject()
                .put("pairingId", receiver.pairingId)
                .put("publicKey", Base64.getEncoder().encodeToString(receiver.publicKey))
                .toString()
        context
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(key, value)
            ?.apply()
    }

    fun remove(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        controllerId: String,
    ) {
        val key = key(endpoint, controllerId) ?: return
        context
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(key)
            ?.apply()
    }

    companion object {
        private const val PREFS = "airplay_receiver_pairings"

        fun key(
            endpoint: Iap2SenderSession.CarPlayEndpoint,
            controllerId: String,
        ): String? {
            val instance =
                endpoint.deviceIdentifier?.takeIf { it.isNotBlank() }?.let { "id:${it.lowercase(Locale.ROOT)}" }
                    ?: endpoint.publicKey?.takeIf { it.isNotBlank() }?.let { "pk:${it.lowercase(Locale.ROOT)}" }
                    ?: return null
            return MessageDigest
                .getInstance("SHA-256")
                .digest("$controllerId\n$instance".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

package com.mcarplay.app.shizuku

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log

/** Capture playback from the dashboard's installed map apps on a dedicated route. */
internal class ShellNavigationAudio(
    private val context: Context,
) {
    private class Stream(
        val owner: IBinder,
        val death: IBinder.DeathRecipient,
        val record: AudioRecord,
        val writer: ParcelFileDescriptor,
        val unregister: () -> Unit,
    )

    private var stream: Stream? = null

    @Synchronized
    fun open(
        sampleRate: Int,
        owner: IBinder,
        mapUids: IntArray,
    ): ParcelFileDescriptor? {
        if (sampleRate !in setOf(44_100, 48_000)) return null
        if (mapUids.isEmpty()) return null

        stream?.let(::retire)
        var unregister: (() -> Unit)? = null
        var recorder: AudioRecord? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        val death = IBinder.DeathRecipient { close(owner) }
        return try {
            val shell = CaptureContext(context.createPackageContext("com.android.shell", 0))
            val manager = AudioManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(shell)
            val (policy, mix) = createPolicy(shell, sampleRate, mapUids)
            val policyType = policy.javaClass
            val managerType = AudioManager::class.java
            val unregisterMethod = managerType.getMethod("unregisterAudioPolicy", policyType)
            val releasePolicy: () -> Unit = {
                unregisterMethod.invoke(manager, policy)
            }
            unregister = releasePolicy
            val result = managerType.getMethod("registerAudioPolicy", policyType).invoke(manager, policy)
            check(result == 0) { "Audio policy registration failed ($result)" }

            val record = createSink(policy, mix, shell)
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate == sampleRate && record.channelCount == 2)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val descriptors = ParcelFileDescriptor.createPipe()
            pipe = descriptors
            owner.linkToDeath(death, 0)
            val active = Stream(owner, death, record, descriptors[1], releasePolicy)
            stream = active
            Thread({ copyPcm(active) }, "car-navigation-audio").apply {
                isDaemon = true
                start()
            }
            descriptors[0]
        } catch (error: Exception) {
            stream = null
            runCatching { owner.unlinkToDeath(death, 0) }
            pipe?.forEach { runCatching { it.close() } }
            runCatching { recorder?.release() }
            runCatching { unregister?.invoke() }
            Log.w(TAG, "Cannot open navigation audio", error)
            null
        }
    }

    private class CaptureContext(
        base: Context,
    ) : ContextWrapper(base) {
        private val source = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()

        override fun getApplicationContext(): Context = this

        override fun getOpPackageName(): String = "com.android.shell"

        override fun getAttributionSource(): AttributionSource = source
    }

    private fun createPolicy(
        context: Context,
        sampleRate: Int,
        uids: IntArray,
    ): Pair<Any, Any> {
        val policyBuilderType = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val mixType = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderType = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderType = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderType.getConstructor().newInstance()
        val addRule = ruleBuilderType.getMethod("addMixRule", Integer.TYPE, Any::class.java)
        val usageRule = ruleType.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        // Some maps send announcements as ordinary media or use the default usage.
        for (usage in MAP_PLAYBACK_USAGES) {
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            addRule.invoke(ruleBuilder, usageRule, attributes)
        }
        val uidRule = ruleType.getField("RULE_MATCH_UID").getInt(null)
        uids.forEach { addRule.invoke(ruleBuilder, uidRule, it) }
        val rule = ruleBuilderType.getMethod("build").invoke(ruleBuilder)
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        val mixBuilder = mixBuilderType.getConstructor(ruleType).newInstance(rule)
        mixBuilderType.getMethod("setRouteFlags", Integer.TYPE).invoke(mixBuilder, mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null))
        mixBuilderType.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        val mix = checkNotNull(mixBuilderType.getMethod("build").invoke(mixBuilder))
        val policyBuilder = policyBuilderType.getConstructor(Context::class.java).newInstance(context)
        policyBuilderType.getMethod("addMix", mixType).invoke(policyBuilder, mix)
        return checkNotNull(policyBuilderType.getMethod("build").invoke(policyBuilder)) to mix
    }

    private fun createSink(
        policy: Any,
        mix: Any,
        context: Context,
    ): AudioRecord {
        val policyType = policy.javaClass
        val mixType = mix.javaClass
        val builderType = AudioRecord.Builder::class.java
        val sink =
            try {
                val method = policyType.getMethod("createAudioRecordSink", mixType, builderType)
                val builder = AudioRecord.Builder()
                builderType.getMethod("setContext", Context::class.java).invoke(builder, context)
                method.invoke(policy, mix, builder)
            } catch (_: NoSuchMethodException) {
                policyType.getMethod("createAudioRecordSink", mixType).invoke(policy, mix)
            }
        return checkNotNull(sink as? AudioRecord) { "The system did not create a navigation audio sink" }
    }

    private fun copyPcm(active: Stream) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(active.writer).use { output ->
                val chunk = ByteArray(4096)
                while (true) {
                    val count = active.record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) break
                    output.write(chunk, 0, count)
                }
            }
        } catch (_: Exception) {
            // The caller closed its descriptor or the recorder stopped.
        } finally {
            synchronized(this) {
                if (stream === active) retire(active)
            }
        }
    }

    @Synchronized
    fun close(owner: IBinder) {
        stream?.takeIf { it.owner == owner }?.let(::retire)
    }

    private fun retire(active: Stream) {
        stream = null
        runCatching { active.owner.unlinkToDeath(active.death, 0) }
        runCatching { active.record.stop() }
        runCatching { active.writer.close() }
        runCatching { active.record.release() }
        runCatching { active.unregister() }
    }

    private companion object {
        const val TAG = "CarNavAudio"
        val MAP_PLAYBACK_USAGES =
            intArrayOf(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            )
    }
}

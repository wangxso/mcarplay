package com.mcarplay.app.session

internal interface SessionUiPort<D> {
    fun phase(hint: String?)

    fun hint(message: () -> String)

    fun ssidChanged(ssid: () -> String)

    fun ask(
        spec: D,
        onChoice: (Boolean?) -> Unit,
    ): Boolean

    fun dismiss(requestId: Long)
}

internal class LegacySessionUiPort<D>(
    private val setPhase: (String?) -> Unit,
    private val hintSink: () -> ((String) -> Unit)?,
    private val ssidSink: () -> ((String) -> Unit)?,
    private val dialogSink: () -> ((D, (Boolean?) -> Unit) -> Unit)?,
    private val dismissSink: () -> ((Long) -> Unit)?,
) : SessionUiPort<D> {
    override fun phase(hint: String?) {
        setPhase(hint)
    }

    override fun hint(message: () -> String) {
        val sink = hintSink() ?: return
        sink(message())
    }

    override fun ssidChanged(ssid: () -> String) {
        val sink = ssidSink() ?: return
        sink(ssid())
    }

    override fun ask(
        spec: D,
        onChoice: (Boolean?) -> Unit,
    ): Boolean {
        val sink = dialogSink() ?: return false
        sink(spec, onChoice)
        return true
    }

    override fun dismiss(requestId: Long) {
        val sink = dismissSink() ?: return
        sink(requestId)
    }
}

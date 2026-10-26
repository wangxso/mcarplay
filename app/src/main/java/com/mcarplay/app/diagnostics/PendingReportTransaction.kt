package com.mcarplay.app.diagnostics

/** A report becomes visible only after the entire write succeeds; failed pending items are removed. */
internal object PendingReportTransaction {
    fun <T : Any> write(
        create: () -> T?,
        write: (T) -> Unit,
        publish: (T) -> Boolean,
        delete: (T) -> Unit,
    ): T {
        val item = checkNotNull(create()) { "Cannot create pending diagnostic report" }
        try {
            write(item)
            check(publish(item)) { "Cannot publish diagnostic report" }
            return item
        } catch (error: Exception) {
            runCatching { delete(item) }
            throw error
        }
    }
}

package com.mcarplay.app.compat

import com.mcarplay.app.audio.AudioProfile

/** 检测只选择已经实现的协议变体，不从超时或状态错误推断新能力。 */
object ReceiverProbePlan {
    fun alternativeInfo(
        current: InfoRequest,
        status: String,
        detecting: Boolean,
    ): InfoRequest? {
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        if (code == null || code == 200 || code == 403) return null
        if (current == InfoRequest.POST_EMPTY_PLIST) return InfoRequest.GET_EMPTY_PLIST
        return InfoRequest.POST_EMPTY_PLIST.takeIf { detecting && code in listOf(405, 501) }
    }

    fun ports(
        port: Int,
        fallback: Boolean,
        detecting: Boolean,
    ): List<Int> =
        if (port !in 1..65535) {
            emptyList()
        } else if (fallback && detecting) {
            listOf(port, 7001, 7100).distinct()
        } else {
            listOf(port)
        }

    fun audio(
        candidates: List<AudioProfile>,
        preferred: String?,
    ): List<AudioProfile> = (listOfNotNull(candidates.firstOrNull { it.key == preferred }) + candidates).distinctBy { it.key }
}

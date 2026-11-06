package com.mcarplay.app.airplay

/**
 * CarPlay 资源仲裁（主屏 resourceID=1、主音频 resourceID=2）的手机侧状态。
 *
 * 协议角色：车机用 `changeModes` 请求资源、用 `requestUI` 表示用户点了 CarPlay 图标；手机是仲裁方，
 * 用 `modesChanged` 报告**当前**归属（entity：1=手机，2=车机）。旧实现无论车机说什么都回「手机持有」，
 * 帕萨特 2025（VW4211）2026-11-01 实测：
 * - 22:45 一轮 `/info modes` 里车机以 userInitiated(500) 优先级/约束持有主屏，手机开场直接宣称持有
 *   被车机无视：无画面、车机持续要关键帧，点图标 7 次也切不过去；
 * - 22:46 一轮车机只以 niceToHave(100) 持有，开场宣称持有生效：出画、触控 832 条；
 * - 同一轮用户回原车主界面时车机发 `changeModes`（take 主屏，500/500），手机仍回「手机持有」，
 *   之后点图标 6 次都回不到 CarPlay。
 * 这里按报告归属：车机以 userInitiated 拿走时如实回 entity=2，用户点图标（用户发起，满足 500 约束）时
 * 再拿回 entity=1。约束 never(1000) 时不抢。
 *
 * 23:02:42 一轮（上一轮异常断开后车机停在原车界面）：`/info` 主屏 100/100，手机开场宣称持有，
 * 车机仍持续要关键帧；用户点图标时手机状态没变、重发同样的 modesChanged 被车机无视。用户点图标本身
 * 说明车机此刻在原车界面，所以「本就归手机」时要先报一次让出、再报拿回，让车机看到一次真实的归属切换。
 */
internal class ModeArbiter {
    enum class Entity(
        val code: Long,
    ) {
        CONTROLLER(1L),
        ACCESSORY(2L),
    }

    private class Resource(
        var owner: Entity = Entity.CONTROLLER,
        var permanent: Entity = Entity.CONTROLLER,
        var borrowed: Boolean = false,
        var takeConstraint: Int = CONSTRAINT_ANYTIME,
        var borrowConstraint: Int = CONSTRAINT_ANYTIME,
        var unborrowConstraint: Int = CONSTRAINT_ANYTIME,
    )

    private val resources = linkedMapOf(SCREEN to Resource(), MAIN_AUDIO to Resource())
    private var screenRelinquished = false
    private var audioClaimPending = false

    /** 屏幕当前是否归手机（诊断与是否值得补关键帧）。 */
    @get:Synchronized
    val phoneOwnsScreen: Boolean get() = resources.getValue(SCREEN).owner == Entity.CONTROLLER

    /** 主音频资源当前是否归手机；车机占用/借用时音频 SETUP 拒绝不是格式不兼容。 */
    @get:Synchronized
    val phoneOwnsAudio: Boolean get() = resources.getValue(MAIN_AUDIO).owner == Entity.CONTROLLER

    /** 音频 claim 曾因约束被拒、仍在等车机归还：调用方不应把本次未启动记成音频故障。 */
    @get:Synchronized
    val audioClaimAwaitingReturn: Boolean get() = audioClaimPending

    /** `/info` 的 `modes`：车机开场的资源请求。返回一行说明。 */
    @Synchronized
    fun applyInitial(modes: Map<*, *>?): String = applyRequests(modes, initial = true)

    /** 车机 `changeModes` 的 params。返回一行说明。 */
    @Synchronized
    fun onChangeModes(params: Map<*, *>?): String = applyRequests(params, initial = false)

    /** [onUserRequestedUi] 的结果：[retake] = 主屏本就归手机，需先报让出再报拿回。 */
    class UiRequest(
        val note: String,
        val retake: Boolean,
        val rebuildStream: Boolean = false,
        val allowed: Boolean = true,
    )

    /** 用户在车机上点了 CarPlay 图标：用户发起的拿回主屏。 */
    @Synchronized
    fun onUserRequestedUi(): UiRequest {
        val screen = resources.getValue(SCREEN)
        if (screen.owner == Entity.CONTROLLER) {
            return UiRequest("主屏本就归手机，先报让出再拿回", retake = true, rebuildStream = screenRelinquished)
        }
        val constraint = if (screen.borrowed) screen.unborrowConstraint else screen.takeConstraint
        if (constraint > PRIORITY_USER_INITIATED) {
            return UiRequest("车机持有主屏的约束高于 userInitiated，不抢", retake = false, allowed = false)
        }
        screen.owner = Entity.CONTROLLER
        screen.permanent = Entity.CONTROLLER
        screen.borrowed = false
        val rebuild = screenRelinquished
        return UiRequest("用户点图标，拿回主屏（车机约束 $constraint ≤ userInitiated）", retake = false, rebuildStream = rebuild)
    }

    /** [claimed] = 是否真的改成手机持有；车机约束高于 userInitiated 时保持原归属。 */
    class AudioClaim(
        val note: String,
        val claimed: Boolean,
    )

    /**
     * 建立主音频流前由手机接管主音频。
     *
     * 真 iPhone 的顺序是先报 `mainAudio controller`，约 2.5 s 后才 `Main audio setting up`。
     * 若 `/info` 里主音频仍归车机（take）或被车机借用（borrow），直接发音频 `SETUP` 时
     * VW4131 会以 `400 Bad Request` 拒绝三档候选；先报归手机可避免把资源仲裁失败误判成编码或格式问题。
     * 但车机以高于 userInitiated 的约束持有或借用（例如倒车提示音）时不能抢占，如实保持原归属。
     */
    @Synchronized
    fun onAudioStreamStarting(): AudioClaim {
        val audio = resources.getValue(MAIN_AUDIO)
        if (audio.owner == Entity.CONTROLLER) {
            audioClaimPending = false
            return AudioClaim("主音频本就归手机", claimed = true)
        }
        val constraint = if (audio.borrowed) audio.unborrowConstraint else audio.takeConstraint
        if (constraint > PRIORITY_USER_INITIATED) {
            audioClaimPending = true
            return AudioClaim("车机持有主音频的约束 $constraint 高于 userInitiated，不抢占", claimed = false)
        }
        audio.owner = Entity.CONTROLLER
        audio.permanent = Entity.CONTROLLER
        audio.borrowed = false
        audio.takeConstraint = CONSTRAINT_ANYTIME
        audioClaimPending = false
        return AudioClaim("音频流建立前接管主音频（车机约束 $constraint ≤ userInitiated）", claimed = true)
    }

    /**
     * claim 因约束被拒后，约束已解除或主音频已归还时返回 true，调用方应重试建立音频流。
     * 只消费一次；主音频仍被高约束占用时保持待重试状态。
     */
    @Synchronized
    fun consumeAudioClaimRetry(): Boolean {
        if (!audioClaimPending) return false
        val audio = resources.getValue(MAIN_AUDIO)
        val constraint = if (audio.borrowed) audio.unborrowConstraint else audio.takeConstraint
        // 归还（owner=phone）或约束降到 userInitiated 及以下都值得重试；两者都不满足时
        // 继续等待，避免每次 changeModes 都白跑一次 SETUP。
        if (audio.owner != Entity.CONTROLLER && constraint > PRIORITY_USER_INITIATED) return false
        audioClaimPending = false
        return true
    }

    /**
     * 用户在 CarWith 点了「返回车机」：主屏与主音频都报归车机，与真 iPhone 点车机原生图标回到车机界面一致。
     * takeConstraint 放宽到 anytime，用户之后再点 CarPlay 图标（requestUI）仍可拿回。
     */
    @Synchronized
    fun onUserReturnedToCar(): String {
        for (resource in resources.values) {
            resource.owner = Entity.ACCESSORY
            resource.permanent = Entity.ACCESSORY
            resource.borrowed = false
            resource.takeConstraint = CONSTRAINT_ANYTIME
        }
        screenRelinquished = true
        return describe()
    }

    @Synchronized
    fun onScreenStreamRestored() {
        if (phoneOwnsScreen) screenRelinquished = false
    }

    /** modesChanged 的 resources 段；[releaseScreen] = 本条报主屏归车机（拿回前的让出），不改记录的归属。 */
    @Synchronized
    fun resourcesPayload(releaseScreen: Boolean = false): List<Map<String, Any?>> =
        resources.map { (id, resource) ->
            val released = releaseScreen && id == SCREEN
            linkedMapOf<String, Any?>(
                "resourceID" to id,
                "entity" to (if (released) Entity.ACCESSORY else resource.owner).code,
                "permanentEntity" to (if (released) Entity.ACCESSORY else resource.permanent).code,
            )
        }

    @Synchronized
    fun describe(): String =
        resources.entries.joinToString(" ") { (id, resource) ->
            val name = if (id == SCREEN) "主屏" else "主音频"
            val owner =
                when {
                    resource.owner == Entity.CONTROLLER -> "手机"
                    resource.borrowed -> "车机借用"
                    else -> "车机"
                }
            // 借用期间真正约束 requestUI / 音频 claim 的是 unborrowConstraint。
            val constraint = if (resource.borrowed) resource.unborrowConstraint else resource.takeConstraint
            "$name=$owner(约束 $constraint)"
        }

    private fun applyRequests(
        container: Map<*, *>?,
        initial: Boolean,
    ): String {
        val requests = container?.get("resources") as? List<*> ?: return "无资源请求，保持 ${describe()}"
        for (entry in requests) {
            val request = entry as? Map<*, *> ?: continue
            val resource = resources[(request["resourceID"] as? Number)?.toLong()] ?: continue
            val previousOwner = resource.owner
            val type = (request["transferType"] as? Number)?.toInt() ?: continue
            val priority = (request["transferPriority"] as? Number)?.toInt() ?: PRIORITY_NICE_TO_HAVE
            val takeConstraint = (request["takeConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
            val borrowConstraint = (request["borrowConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
            val unborrowConstraint =
                (request["unborrowConstraint"] as? Number)?.toInt() ?: borrowConstraint
            when (type) {
                TRANSFER_TAKE ->
                    // 开场 niceToHave 的 take = 「没人要就先放车机」：手机要，就归手机（旧行为，22:46 一轮实测可出画）
                    if (initial && priority < PRIORITY_USER_INITIATED) {
                        resource.owner = Entity.CONTROLLER
                        resource.permanent = Entity.CONTROLLER
                        resource.borrowed = false
                        resource.takeConstraint = CONSTRAINT_ANYTIME
                    } else {
                        resource.owner = Entity.ACCESSORY
                        resource.permanent = Entity.ACCESSORY
                        resource.borrowed = false
                        resource.takeConstraint = takeConstraint
                        resource.borrowConstraint = borrowConstraint
                    }
                TRANSFER_UNTAKE -> {
                    resource.owner = Entity.CONTROLLER
                    resource.permanent = Entity.CONTROLLER
                    resource.borrowed = false
                    resource.takeConstraint = CONSTRAINT_ANYTIME
                }
                TRANSFER_BORROW -> {
                    resource.owner = Entity.ACCESSORY
                    resource.borrowed = true
                    resource.borrowConstraint = borrowConstraint
                    resource.unborrowConstraint = unborrowConstraint
                }
                TRANSFER_UNBORROW -> {
                    resource.owner = resource.permanent
                    resource.borrowed = false
                }
            }
            if (!initial &&
                (request["resourceID"] as? Number)?.toLong() == SCREEN &&
                previousOwner == Entity.CONTROLLER &&
                resource.owner == Entity.ACCESSORY
            ) {
                screenRelinquished = true
            }
        }
        return describe()
    }

    companion object {
        const val SCREEN = 1L
        const val MAIN_AUDIO = 2L
        const val TRANSFER_TAKE = 1
        const val TRANSFER_UNTAKE = 2
        const val TRANSFER_BORROW = 3
        const val TRANSFER_UNBORROW = 4
        const val PRIORITY_NICE_TO_HAVE = 100
        const val PRIORITY_USER_INITIATED = 500
        const val CONSTRAINT_ANYTIME = 100
        const val CONSTRAINT_NEVER = 1000
    }
}

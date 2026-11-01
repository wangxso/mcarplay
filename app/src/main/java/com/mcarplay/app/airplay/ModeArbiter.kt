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
        var takeConstraint: Int = CONSTRAINT_ANYTIME,
        var borrowConstraint: Int = CONSTRAINT_ANYTIME,
    )

    private val resources = linkedMapOf(SCREEN to Resource(), MAIN_AUDIO to Resource())

    /** 屏幕当前是否归手机（诊断与是否值得补关键帧）。 */
    @get:Synchronized
    val phoneOwnsScreen: Boolean get() = resources.getValue(SCREEN).owner == Entity.CONTROLLER

    /** `/info` 的 `modes`：车机开场的资源请求。返回一行说明。 */
    @Synchronized
    fun applyInitial(modes: Map<*, *>?): String = applyRequests(modes, initial = true)

    /** 车机 `changeModes` 的 params。返回一行说明。 */
    @Synchronized
    fun onChangeModes(params: Map<*, *>?): String = applyRequests(params, initial = false)

    /** 用户在车机上点了 CarPlay 图标：用户发起的拿回主屏。返回一行说明。 */
    @Synchronized
    fun onUserRequestedUi(): String {
        val screen = resources.getValue(SCREEN)
        if (screen.owner == Entity.CONTROLLER) return "主屏本就归手机"
        if (screen.takeConstraint >= CONSTRAINT_NEVER) return "车机以 never 约束持有主屏，不抢"
        screen.owner = Entity.CONTROLLER
        screen.permanent = Entity.CONTROLLER
        return "用户点图标，拿回主屏（车机约束 ${screen.takeConstraint} ≤ userInitiated）"
    }

    /** modesChanged 的 resources 段。 */
    @Synchronized
    fun resourcesPayload(): List<Map<String, Any?>> =
        resources.map { (id, resource) ->
            linkedMapOf<String, Any?>(
                "resourceID" to id,
                "entity" to resource.owner.code,
                "permanentEntity" to resource.permanent.code,
            )
        }

    @Synchronized
    fun describe(): String =
        resources.entries.joinToString(" ") { (id, resource) ->
            "${if (id == SCREEN) "主屏" else "主音频"}=${if (resource.owner == Entity.CONTROLLER) "手机" else "车机"}" +
                "(约束 ${resource.takeConstraint})"
        }

    private fun applyRequests(
        container: Map<*, *>?,
        initial: Boolean,
    ): String {
        val requests = container?.get("resources") as? List<*> ?: return "无资源请求，保持 ${describe()}"
        for (entry in requests) {
            val request = entry as? Map<*, *> ?: continue
            val resource = resources[(request["resourceID"] as? Number)?.toLong()] ?: continue
            val type = (request["transferType"] as? Number)?.toInt() ?: continue
            val priority = (request["transferPriority"] as? Number)?.toInt() ?: PRIORITY_NICE_TO_HAVE
            val takeConstraint = (request["takeConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
            val borrowConstraint = (request["borrowConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
            when (type) {
                TRANSFER_TAKE ->
                    // 开场 niceToHave 的 take = 「没人要就先放车机」：手机要，就归手机（旧行为，22:46 一轮实测可出画）
                    if (initial && priority < PRIORITY_USER_INITIATED) {
                        resource.owner = Entity.CONTROLLER
                        resource.permanent = Entity.CONTROLLER
                        resource.takeConstraint = CONSTRAINT_ANYTIME
                    } else {
                        resource.owner = Entity.ACCESSORY
                        resource.permanent = Entity.ACCESSORY
                        resource.takeConstraint = takeConstraint
                        resource.borrowConstraint = borrowConstraint
                    }
                TRANSFER_UNTAKE -> {
                    resource.owner = Entity.CONTROLLER
                    resource.permanent = Entity.CONTROLLER
                    resource.takeConstraint = CONSTRAINT_ANYTIME
                }
                TRANSFER_BORROW -> {
                    resource.owner = Entity.ACCESSORY
                    resource.borrowConstraint = borrowConstraint
                }
                TRANSFER_UNBORROW -> resource.owner = resource.permanent
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

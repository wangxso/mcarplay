package com.mcarplay.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知使用权判定（纯函数）：系统把多个监听器用 `:` 拼成一个长串，
 * 必须按条目精确比对——用 `contains(包名)` 会被「前缀相同」的其它应用误判。
 */
class NotificationAccessTest {
    private val spec = "com.mcarplay.app/com.mcarplay.app.media.MediaListenerService"

    @Test
    fun detectsOwnComponentAmongOthers() {
        val enabled =
            "com.google.android.projection.gearhead/GearheadListenerService:" +
                "$spec:com.hihonor.health/HnNotificationListenerService"
        assertTrue(NotificationAccess.isGrantedIn(enabled, spec))
    }

    @Test
    fun detectsWhenOnlyEntry() {
        assertTrue(NotificationAccess.isGrantedIn(spec, spec))
    }

    @Test
    fun rejectsWhenAbsent() {
        assertFalse(NotificationAccess.isGrantedIn("com.other.app/OtherService", spec))
        assertFalse(NotificationAccess.isGrantedIn("", spec))
        assertFalse(NotificationAccess.isGrantedIn(null, spec))
    }

    @Test
    fun doesNotMatchByPrefixOnly() {
        // 旧包名是前缀相同的另一条授权：不能因为字符串包含就判为已授权
        // （改包名后最容易踩的坑——旧条目还在列表里，新包其实没授权）
        val staleOldPackage =
            "com.mcarplay.app/com.mcarplay.app.media.OtherService"
        assertFalse(NotificationAccess.isGrantedIn(staleOldPackage, spec))
    }

    /**
     * 「在授权列表里」不等于「已被系统绑定」：重装后 enabled 列表仍有本组件，
     * 但 Live 段缺失 → metadata 恒为 null、卡片显示「无法读取播放信息」
     * （2026-10-19 实测）。必须只认 Live 段。
     */
    @Test
    fun liveSectionDecidesWhetherMetadataIsReadable() {
        val dump =
            """
            Notification listeners:
              Allowed notification listeners:
                $spec (user: 0 isPrimary: true)
              All notification listeners (2) enabled for current profiles:
                ComponentInfo{$spec}
              Live notification listeners (1):
                ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              Snoozed notification listeners (0):
            """.trimIndent()
        // 授权列表里有、Live 段没有 → 仍是未绑定（必须触发重绑）
        assertFalse(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun liveSectionIsDetectedOnceBound() {
        val dump =
            """
            Live notification listeners (2):
              ComponentInfo{android/com.android.server.SystemNotificationListener} (user -1)
              ComponentInfo{$spec} (user 0): android.service.notification.INotificationListener${'$'}Stub${'$'}Proxy@abc
            Snoozed notification listeners (0):
            """.trimIndent()
        assertTrue(NotificationAccess.isLiveIn(dump, spec))
    }

    @Test
    fun missingLiveSectionIsNotTreatedAsBound() {
        assertFalse(NotificationAccess.isLiveIn("no such section", spec))
    }
}

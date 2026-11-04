package com.mcarplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TimelineDetailPolicyTest {
    @Test
    fun defaultTimelineStillRedactsAtTheSource() {
        val records = mutableListOf<String>()
        val timeline = ConnectionTimeline(sink = records::add)
        timeline.note(timeline.begin("test"), "address=10.2.3.4 psk=SecretValue")
        val detail = JSONObject(records.last()).getString("detail")
        assertFalse(detail.contains("10.2.3.4"))
        assertFalse(detail.contains("SecretValue"))
    }

    @Test
    fun rawDetailFilterRetainsOriginalTextAndMachineReadableJson() {
        val records = mutableListOf<String>()
        val timeline = ConnectionTimeline(sink = records::add, detailFilter = { it })
        val original = "address=10.2.3.4 psk=SecretValue name=\"Family Car\"\nwireHex=01020304"
        timeline.note(timeline.begin("test"), original)
        assertEquals(original, JSONObject(records.last()).getString("detail"))
    }
}

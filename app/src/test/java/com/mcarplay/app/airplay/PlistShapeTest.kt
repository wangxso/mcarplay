package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class PlistShapeTest {
    @Test
    fun describesStructureWithoutContent() {
        val command =
            linkedMapOf(
                "params" to linkedMapOf("data" to ByteArray(42), "name" to "Passat", "uuid" to 2L, "on" to true),
                "list" to listOf(1L, 2L),
            )
        assertEquals("{params={data=data(42B), name=str(6), uuid=2, on=true}, list=[1, 2]}", PlistShape.describe(command))
    }

    @Test
    fun longListsAndDeepNestingAreSummarised() {
        assertEquals("list(20)", PlistShape.describe(List(20) { it.toLong() }))
        val deep = mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to 1L)))))
        assertEquals("{a={b={c={d=dict(1)}}}}", PlistShape.describe(deep))
    }
}

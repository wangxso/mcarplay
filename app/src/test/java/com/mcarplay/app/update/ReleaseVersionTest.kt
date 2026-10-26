package com.mcarplay.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun tagsRoundTripAndMatchGradleVersionCode() {
        val version = requireNotNull(ReleaseVersion.parse("v0.1.1-test.2"))
        assertEquals("0.1.1", version.base)
        assertEquals("v0.1.1-test.2", version.tag)
        assertEquals(1_010_000L, version.versionCode)
        assertEquals(version, ReleaseVersion.parse("0.1.1-test.2"))
    }

    @Test
    fun semanticOrderUsesNumericPreviewAndStableLast() {
        val tags = listOf("v0.1.1-test.2", "v0.1.1-test.10", "v0.1.1", "v0.1.2-test.1", "v0.2.0", "v1.0.0")
        val versions = tags.map { requireNotNull(ReleaseVersion.parse(it)) }
        assertEquals(versions, versions.reversed().sorted())
        assertTrue(versions.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun malformedAndUnrepresentableVersionsAreRejected() {
        listOf(
            "",
            "v1.2",
            "v01.2.3",
            "v1.02.3",
            "v1.2.03",
            "v1.2.3-test.01",
            "v1.2.3-beta.1",
            "v1.2.3.4",
            "v1.2.3+abc",
            "v1.2.3 ",
            "v-1.2.3",
            "v1.100.0",
            "v1.0.100",
            "v2147483648.0.0",
            "v1.2.3-test.2147483648",
            "v100.0.0",
        ).forEach { assertNull(it, ReleaseVersion.parse(it)) }
    }

    @Test
    fun knownTagsAllowSameBasePreviewUpgradeAndStablePromotion() {
        val base = requireNotNull(ReleaseVersion.parse("0.1.1"))
        assertEquals(UpdateRelation.NEWER, compareRelease(base.copy(test = 3), base, "v0.1.1-test.2"))
        assertEquals(UpdateRelation.NEWER, compareRelease(base, base, "v0.1.1-test.2"))
        assertEquals(UpdateRelation.SAME, compareRelease(base, base, "v0.1.1"))
        assertEquals(UpdateRelation.OLDER, compareRelease(base.copy(test = 3), base, "v0.1.1"))
    }

    @Test
    fun unknownLocalTagNeverGuessesPreviewOrder() {
        val base = requireNotNull(ReleaseVersion.parse("0.1.1"))
        listOf("", "invalid", "v0.1.0-test.1").forEach { tag ->
            assertEquals(UpdateRelation.UNKNOWN_SAME_BASE, compareRelease(base.copy(test = 3), base, tag))
        }
        assertEquals(UpdateRelation.NEWER, compareRelease(base.copy(patch = 2, test = 1), base, ""))
        assertEquals(UpdateRelation.OLDER, compareRelease(base.copy(patch = 0), base, ""))
    }
}

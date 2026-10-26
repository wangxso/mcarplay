package com.mcarplay.app.update

data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val test: Int?,
) : Comparable<ReleaseVersion> {
    val base: String get() = "$major.$minor.$patch"
    val tag: String get() = "v$base" + (test?.let { "-test.$it" } ?: "")
    val versionCode: Long get() = (major * 10_000L + minor * 100L + patch) * 10_000L

    override fun compareTo(other: ReleaseVersion): Int {
        val baseOrder = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (baseOrder != 0) return baseOrder
        return when {
            test == null && other.test == null -> 0
            test == null -> 1
            other.test == null -> -1
            else -> test.compareTo(other.test)
        }
    }

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match =
                Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-test\\.(0|[1-9][0-9]*))?")
                    .matchEntire(value) ?: return null
            val parts =
                match.groupValues
                    .drop(1)
                    .take(3)
                    .map { it.toIntOrNull() ?: return null }
            val test = match.groupValues[4].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null }
            // Match this project's versionCode encoding without allowing component collisions or overflow.
            if (parts[1] > 99 || parts[2] > 99) return null
            return ReleaseVersion(parts[0], parts[1], parts[2], test).takeIf { it.versionCode <= Int.MAX_VALUE }
        }
    }
}

enum class UpdateRelation { NEWER, SAME, OLDER, UNKNOWN_SAME_BASE }

fun compareRelease(
    candidate: ReleaseVersion,
    installedBase: ReleaseVersion,
    installedTag: String,
): UpdateRelation {
    val tagged = ReleaseVersion.parse(installedTag)?.takeIf { it.base == installedBase.base }
    if (tagged == null && candidate.base == installedBase.base) return UpdateRelation.UNKNOWN_SAME_BASE
    val comparison = candidate.compareTo(tagged ?: installedBase)
    return when {
        comparison > 0 -> UpdateRelation.NEWER
        comparison < 0 -> UpdateRelation.OLDER
        else -> UpdateRelation.SAME
    }
}

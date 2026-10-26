package com.mcarplay.app.update

import org.junit.Test

class ApkUpdateVerifierTest {
    private val version = requireNotNull(ReleaseVersion.parse("v0.1.2-test.2"))
    private val candidate = ApkIdentity("com.mcarplay.app", "0.1.2", 1_020_000, setOf("certificate"), 31)
    private val installed = candidate.copy(versionName = "0.1.1", versionCode = 1_010_000)

    @Test
    fun acceptsSameSignerUpgradeAndEqualCodePreviewIteration() {
        ApkUpdateVerifier.requireCompatible(installed, candidate, version, 34)
        ApkUpdateVerifier.requireCompatible(candidate, candidate, version, 34)
    }

    @Test
    fun wrongPackageAndRemoteFlavorCannotBeInstalled() {
        expectUpdateError("PACKAGE_MISMATCH") {
            ApkUpdateVerifier.requireCompatible(installed.copy(packageName = "com.mcarplay.app.rd"), candidate, version, 34)
        }
        expectUpdateError("PACKAGE_MISMATCH") {
            ApkUpdateVerifier.requireCompatible(installed, candidate.copy(packageName = "attacker"), version, 34)
        }
    }

    @Test
    fun tagMustMatchBothVersionNameAndDerivedCode() {
        listOf(candidate.copy(versionName = "0.1.3"), candidate.copy(versionCode = candidate.versionCode + 1)).forEach {
            expectUpdateError("VERSION_MISMATCH") { ApkUpdateVerifier.requireCompatible(installed, it, version, 34) }
        }
    }

    @Test
    fun downgradeAndUnsupportedSdkAreRejected() {
        expectUpdateError("DOWNGRADE") {
            ApkUpdateVerifier.requireCompatible(installed.copy(versionCode = candidate.versionCode + 1), candidate, version, 34)
        }
        expectUpdateError("SDK_UNSUPPORTED") {
            ApkUpdateVerifier.requireCompatible(installed, candidate.copy(minSdk = 35), version, 34)
        }
    }

    @Test
    fun missingChangedAndAdditionalSignersAreRejected() {
        listOf(emptySet(), setOf("other"), setOf("certificate", "other")).forEach { signers ->
            expectUpdateError("SIGNATURE_MISMATCH") {
                ApkUpdateVerifier.requireCompatible(installed, candidate.copy(signerDigests = signers), version, 34)
            }
        }
        expectUpdateError("SIGNATURE_MISMATCH") {
            ApkUpdateVerifier.requireCompatible(installed.copy(signerDigests = emptySet()), candidate, version, 34)
        }
    }
}

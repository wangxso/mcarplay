package com.mcarplay.app.iap2

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiHandoffPolicyTest {
    @Test
    fun accessoryCredentialsAlwaysUseTheJoinPathEvenForOpenNetworks() {
        assertEquals(
            WifiHandoffAction.JOIN_ACCESSORY,
            decide(WifiCredentialSource.ACCESSORY, liveSsidMatches = null, liveWifiPresent = false),
        )
    }

    @Test
    fun knownSsidWithoutReadableLocationUsesAFreshInviteInsteadOfJoiningOpen() {
        assertEquals(
            WifiHandoffAction.BIND_CURRENT,
            decide(
                WifiCredentialSource.KNOWN_SSID,
                liveSsidMatches = null,
                liveWifiPresent = true,
                freshInvite = true,
            ),
        )
    }

    @Test
    fun unverifiedWifiIsNeverBoundAutomatically() {
        assertEquals(
            WifiHandoffAction.ASK_CONFIRM_CURRENT,
            decide(
                WifiCredentialSource.KNOWN_SSID,
                liveSsidMatches = null,
                liveWifiPresent = true,
                freshInvite = false,
            ),
        )
        assertEquals(
            WifiHandoffAction.ASK_JOIN_KNOWN,
            decide(
                WifiCredentialSource.KNOWN_SSID,
                liveSsidMatches = false,
                liveWifiPresent = true,
                freshInvite = false,
            ),
        )
    }

    @Test
    fun knownSsidWithMatchingLiveNetworkIsBoundNotJoined() {
        assertEquals(
            WifiHandoffAction.BIND_KNOWN,
            decide(WifiCredentialSource.KNOWN_SSID, liveSsidMatches = true, liveWifiPresent = true),
        )
    }

    @Test
    fun cachedCredentialsRequireTheSameTargetBeforeTheJoinPath() {
        assertEquals(
            WifiHandoffAction.JOIN_CACHED,
            decide(
                WifiCredentialSource.KNOWN_SSID,
                liveSsidMatches = false,
                liveWifiPresent = true,
                cachedCredentials = true,
            ),
        )
        assertEquals(
            WifiHandoffAction.ASK_JOIN_KNOWN,
            decide(
                WifiCredentialSource.KNOWN_SSID,
                liveSsidMatches = false,
                liveWifiPresent = true,
                cachedCredentials = false,
            ),
        )
    }

    @Test
    fun inviteCanBindAnUnlabeledCurrentNetworkButNoInviteWaits() {
        assertEquals(
            WifiHandoffAction.BIND_CURRENT,
            decide(WifiCredentialSource.NONE, liveSsidMatches = null, liveWifiPresent = true, freshInvite = true),
        )
        assertEquals(
            WifiHandoffAction.WAIT,
            decide(WifiCredentialSource.NONE, liveSsidMatches = null, liveWifiPresent = true, freshInvite = false),
        )
    }

    @Test
    fun staleRoundOrWrongTargetIsIgnored() {
        assertEquals(
            WifiHandoffAction.IGNORE,
            WifiHandoffPolicy.decide(
                source = WifiCredentialSource.ACCESSORY,
                targetCurrent = false,
                liveSsidMatches = true,
                liveWifiPresent = true,
                freshInvite = true,
                cachedCredentials = true,
            ),
        )
    }

    @Test
    fun cancelledRoundCannotReviveFromCachedCredentialsOrAFreshInvite() {
        assertEquals(
            WifiHandoffAction.IGNORE,
            WifiHandoffPolicy.decide(
                source = WifiCredentialSource.KNOWN_SSID,
                targetCurrent = false,
                liveSsidMatches = null,
                liveWifiPresent = true,
                freshInvite = true,
                cachedCredentials = true,
            ),
        )
    }

    private fun decide(
        source: WifiCredentialSource,
        liveSsidMatches: Boolean?,
        liveWifiPresent: Boolean,
        freshInvite: Boolean = false,
        cachedCredentials: Boolean = false,
    ): WifiHandoffAction =
        WifiHandoffPolicy.decide(
            source = source,
            targetCurrent = true,
            liveSsidMatches = liveSsidMatches,
            liveWifiPresent = liveWifiPresent,
            freshInvite = freshInvite,
            cachedCredentials = cachedCredentials,
        )
}

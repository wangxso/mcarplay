package com.mcarplay.app

import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.compat.ReceiverObservation

/** The screen ended while its AirPlay client can still receive a reconnect invitation. */
internal class EndedScreen<N : Any>(
    val client: AirPlayClient,
    private val inviteBeforeStream: CtrlInviteEvidence<N>?,
    private val handoff: SessionHandoffOwnership,
) {
    fun bufferedInvite(latest: CtrlInviteEvidence<N>?): CtrlInviteEvidence<N>? =
        latest?.takeIf { it !== inviteBeforeStream && client.controlChannelClosed }

    fun beginReconnect(
        observation: ReceiverObservation?,
        invite: CtrlInviteEvidence<N>,
        retry: (CtrlInviteEvidence<N>) -> Unit,
    ): Boolean =
        invite !== inviteBeforeStream &&
            handoff.begin(
                observation,
                retryWhenReleased = { retry(invite) },
                ownerContext = client.explicitStartScreenClaimLease,
            )
}

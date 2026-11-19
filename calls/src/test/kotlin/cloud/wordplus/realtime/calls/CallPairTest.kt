package cloud.wordplus.realtime.calls

import livekit.org.webrtc.RTCStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

/**
 * Which candidate pair a stats report says the connection is using ([CallPair]), as the direct call's report, round
 * trip and bandwidth estimates read it, the same rule as the realtime client's calls/pair.ts.
 * A Firefox side of a call on 2026-10-26 reported "relay, relay, udp" and a 1.616 s round trip for the whole call while
 * it ran srflx to srflx: the relay pair that won the first checks stayed nominated, and the last nominated pair won.
 */
class CallPairTest {
    private val candidates = listOf(
        CallPair.Entry("Lr", "local-candidate", mapOf("candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "udp")),
        CallPair.Entry("Rr", "remote-candidate", mapOf("candidateType" to "relay", "protocol" to "udp")),
        CallPair.Entry("Ls", "local-candidate", mapOf("candidateType" to "srflx", "protocol" to "udp")),
        CallPair.Entry("Rs", "remote-candidate", mapOf("candidateType" to "srflx", "protocol" to "udp"))
    )

    /** The relay pair that won the first checks, nominated and left there. */
    private fun stale(vararg more: Pair<String, Any?>) = CallPair.Entry("Prelay", "candidate-pair", mapOf(
        "state" to "succeeded", "nominated" to true, "localCandidateId" to "Lr", "remoteCandidateId" to "Rr",
        "currentRoundTripTime" to 1.616, "lastPacketReceivedTimestamp" to 1_000.0, "bytesReceived" to BigInteger.valueOf(2_000)
    ) + more)

    /** The pair the call runs on. */
    private fun direct(vararg more: Pair<String, Any?>) = CallPair.Entry("Pdirect", "candidate-pair", mapOf(
        "state" to "succeeded", "nominated" to true, "localCandidateId" to "Ls", "remoteCandidateId" to "Rs",
        "currentRoundTripTime" to 0.054, "lastPacketReceivedTimestamp" to 90_000.0, "bytesReceived" to BigInteger.valueOf(3_600_000)
    ) + more)

    @Test
    fun theTransportsPairWinsInAnyOrder() {
        val transport = CallPair.Entry("T1", "transport", mapOf("selectedCandidatePairId" to "Pdirect"))
        // its stale pair heard last and moved more, so only the transport's word picks the other
        val louder = stale("lastPacketReceivedTimestamp" to 99_000.0, "bytesReceived" to BigInteger.valueOf(9_000_000))

        assertEquals("Pdirect", CallPair.selected(candidates + listOf(transport, direct(), louder)))
        assertEquals("Pdirect", CallPair.selected(candidates + listOf(louder, direct(), transport)))
    }

    @Test
    fun aPairMarkedSelectedWinsOverOneOnlyOnceNominated() {
        val marked = direct("selected" to true)
        val unmarked = stale("selected" to false, "lastPacketReceivedTimestamp" to 99_000.0)

        assertEquals("Pdirect", CallPair.selected(candidates + listOf(marked, unmarked)))
        assertEquals("Pdirect", CallPair.selected(candidates + listOf(unmarked, marked)))
    }

    @Test
    fun withNothingElseTheNominatedPairThatHeardLastWins() {
        assertEquals("Pdirect", CallPair.selected(candidates + listOf(direct(), stale())))
        assertEquals("Pdirect", CallPair.selected(candidates + listOf(stale(), direct())))

        // without timestamps, the one that took in the most
        val quiet = "lastPacketReceivedTimestamp" to null

        assertEquals("Pdirect", CallPair.selected(candidates + listOf(direct(quiet), stale(quiet))))
        assertEquals("Pdirect", CallPair.selected(candidates + listOf(stale(quiet), direct(quiet))))
    }

    @Test
    fun noPairBeforeOneIsChosen() {
        val checking = listOf(
            CallPair.Entry("T1", "transport", emptyMap()),
            direct("state" to "in-progress"),
            stale("nominated" to false)
        )

        assertNull(CallPair.selected(candidates + checking))
        assertNull(CallPair.selected(emptyList<CallPair.Entry>()))
    }

    @Test
    fun readsAReportAsWebRtcHandsItOver() {
        // the stale pair keyed first and last, as a hash map may list it either way
        for (staleFirst in listOf(true, false)) {
            val entries = candidates + if (staleFirst) listOf(stale(), direct("selected" to true)) else listOf(direct("selected" to true), stale())
            val report = LinkedHashMap<String, RTCStats>()

            for (entry in entries) {
                @Suppress("UNCHECKED_CAST")
                report[entry.id] = RTCStats(0L, entry.type, entry.id, entry.values.filterValues { it != null } as Map<String, Any>)
            }

            val pair = CallPair.selected(report)

            assertEquals("Pdirect", pair?.id)
            assertEquals(0.054, pair?.members?.get("currentRoundTripTime"))
        }
    }
}

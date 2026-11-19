package cloud.wordplus.realtime.calls

import livekit.org.webrtc.RTCStats

/**
 * The candidate pair a peer connection is using, of one stats report's, as
 * the web's call engine reads it.
 *
 * The transport's own word first: its `selectedCandidatePairId`, which
 * WebRTC gives once it has chosen. A pair marked `selected` next, as a
 * browser marks it where it has no transport entry (Firefox). `nominated`
 * says the least, since a pair stays nominated after the connection moves
 * off it: Firefox's side of a call that ran direct reported the relay pair
 * that won the first checks, and that pair's round trip frozen at 1.6 s, for
 * the whole call, because whichever nominated pair the report listed last
 * won — and a report's order here is a hash map's, none at all. So a
 * nominated pair counts only where neither of the others says anything, and
 * then the one that heard from the other side last.
 */
internal object CallPair {
    class Entry(val id: String, val type: String, val values: Map<String, Any?>)

    /** The pair's id, null before there is one. */
    fun selected(entries: Collection<Entry>): String? {
        var transport: String? = null
        val pairs = ArrayList<Entry>()

        for (entry in entries) {
            when (entry.type) {
                "transport" -> if (transport == null) transport = entry.values["selectedCandidatePairId"] as? String
                "candidate-pair" -> pairs.add(entry)
            }
        }

        val named = transport

        if (named != null && pairs.any { it.id == named }) return named

        pairs.firstOrNull { it.values["selected"] == true }?.let { return it.id }

        var best: Entry? = null

        for (pair in pairs) {
            if (pair.values["state"] != "succeeded" || pair.values["nominated"] != true) continue

            val current = best

            if (current == null) {
                best = pair
                continue
            }

            val heard = number(pair.values["lastPacketReceivedTimestamp"])
            val heardBest = number(current.values["lastPacketReceivedTimestamp"])

            if (heard > heardBest || (heard == heardBest && number(pair.values["bytesReceived"]) > number(current.values["bytesReceived"]))) {
                best = pair
            }
        }

        return best?.id
    }

    /** The pair itself, of a report as WebRTC hands it over. */
    fun selected(report: Map<String, RTCStats>): RTCStats? =
        selected(report.values.map { Entry(it.id, it.type, it.members) })?.let { report[it] }

    private fun number(value: Any?): Double = (value as? Number)?.toDouble() ?: 0.0
}

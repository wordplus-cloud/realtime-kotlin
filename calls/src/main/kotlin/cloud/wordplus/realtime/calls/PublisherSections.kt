package cloud.wordplus.realtime.calls

/** What a room publisher's offer says of its sections ([RoomPublisher]). */
internal object PublisherSections {
    /**
     * Whether an offer leaves a camera's or a screen's section open, its port not 0, that no video track this app has
     * published now holds, by the mids of [held]: the transceiver of a track taken down, which the room client stops and
     * libwebrtc then drops, and which a new publish would take over unless an offer has closed it first. Whatever the
     * section says it sends, as the offer may have been made before its track went. A microphone taken down is no such
     * section, as the client stops no audio transceiver.
     */
    @JvmStatic
    fun unheldVideo(sdp: String, held: Set<String>): Boolean = sections(sdp).any { section ->
        val media = section.first().removePrefix("m=").split(' ')
        val mid = section.firstOrNull { it.startsWith("a=mid:") }?.removePrefix("a=mid:")
        media.firstOrNull() == "video" && media.getOrNull(1) != "0" && mid !in held
    }

    private fun sections(sdp: String): List<List<String>> = sdp.split("\r\n", "\n").fold(mutableListOf<MutableList<String>>()) { sections, line ->
        if (line.startsWith("m=")) sections.add(mutableListOf(line)) else sections.lastOrNull()?.add(line)
        sections
    }
}

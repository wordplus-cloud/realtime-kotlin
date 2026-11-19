package cloud.wordplus.realtime.calls;

import io.livekit.android.room.PeerConnectionTransport;
import io.livekit.android.room.Room;
import io.livekit.android.room.track.LocalVideoTrack;
import io.livekit.android.room.track.TrackPublication;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;
import livekit.org.webrtc.PeerConnection;
import livekit.org.webrtc.RtpTransceiver;
import livekit.org.webrtc.SessionDescription;

/**
 * What a group call needs of the room client's publisher that the client keeps to itself, by the names it compiles to, in
 * Java as Kotlin can't name them: a build against a client that lacks one fails here.
 *
 * <p>The room client stops a camera's transceiver a moment after taking its track off, in a step of its own, and the offer
 * the microphone taken down just before asks for can come before the camera's track went or between the two, as when a host
 * makes someone with both on a listener. That offer leaves the camera's section open, libwebrtc drops the stopped
 * transceiver once it is answered, the client offers nothing more, and the next publish takes that section over, which
 * libwebrtc refuses ("The order of m-lines in subsequent offer doesn't match order from previous offer/answer"). From then
 * on nothing this app publishes goes out. So before a group call publishes, a section left so is closed by an offer of its
 * own (GroupCall's beforePublish).
 *
 * <p>An offer is asked for only while the publisher is idle: the client's offers are debounced, and one asked for while
 * another is being made cancels that one after it is set but before it is sent, which leaves the publisher waiting for an
 * answer that never comes.
 */
final class RoomPublisher {
    private RoomPublisher() {}

    /** The publisher is stable, and nothing it holds can be taken over: publish. */
    static final int SETTLED = 0;
    /** An offer is under way: wait for its answer. */
    static final int BUSY = 1;
    /** Stable, with a camera's or a screen's section left open that no track holds: an offer of its own closes it. */
    static final int OPEN = 2;

    /** Has the room's publisher offer again. Only while it is idle ({@link #look}). */
    static void negotiate(Room room) {
        room.getEngine$livekit_android_sdk_release().negotiatePublisher$livekit_android_sdk_release();
    }

    /**
     * Tells where the room's publisher is, as {@link #SETTLED}, {@link #BUSY} or {@link #OPEN}: settled with no publisher, a
     * closed one, or one that could not be read. Told on the client's own thread, or at once.
     */
    static void look(Room room, Function1<Integer, Unit> told) {
        PeerConnectionTransport publisher = room.getEngine$livekit_android_sdk_release().getPublisher$livekit_android_sdk_release();
        if (publisher == null) {
            told.invoke(SETTLED);
            return;
        }
        Continuation<Integer> answer = new Continuation<Integer>() {
            @Override
            public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(Object result) {
                told.invoke(result instanceof Integer ? (Integer) result : SETTLED);
            }
        };
        Object now;
        try {
            now = publisher.<Integer>withPeerConnection((connection, ignored) -> look(room, connection), answer);
        } catch (RuntimeException e) {
            now = SETTLED;
        }
        if (now != IntrinsicsKt.getCOROUTINE_SUSPENDED()) answer.resumeWith(now);
    }

    private static Integer look(Room room, PeerConnection connection) {
        if (connection.signalingState() != PeerConnection.SignalingState.STABLE) return BUSY;
        SessionDescription local = connection.getLocalDescription();
        return local != null && PublisherSections.unheldVideo(local.description, heldVideo(room)) ? OPEN : SETTLED;
    }

    /**
     * The mids of the transceivers the video tracks this app publishes hold, a backup codec's too: read from the tracks, as
     * the connection's own list of transceivers disposes every one it handed out before, the tracks' among them.
     */
    private static Set<String> heldVideo(Room room) {
        Set<String> held = new HashSet<>();
        for (TrackPublication publication : room.getLocalParticipant().getTrackPublications().values()) {
            if (!(publication.getTrack() instanceof LocalVideoTrack)) continue;
            LocalVideoTrack track = (LocalVideoTrack) publication.getTrack();
            List<RtpTransceiver> transceivers = new ArrayList<>(track.getSimulcastTransceivers$livekit_android_sdk_release());
            RtpTransceiver own = track.getTransceiver$livekit_android_sdk_release();
            if (own != null) transceivers.add(own);
            for (RtpTransceiver transceiver : transceivers) {
                String mid = transceiver.getMid();
                if (mid != null) held.add(mid);
            }
        }
        return held;
    }
}

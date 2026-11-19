package cloud.wordplus.realtime.ui.calls

import android.content.Context

/**
 * Running a group call from the phone, as the web runs it (realtime-ui's moderation-panels.tsx): the room's rules in
 * Host Controls, opened from the dock, and one person's actions in their menu, opened from the ⋯ on their tile, each a
 * menu beside its button whose rows say what they do ([CallsPopover]).
 *
 * Every action is the one REST route the web uses, so the permission checks, the room updates and the record left
 * behind are identical whichever screen gave the order. Nothing here decides what someone may do — WordPress does,
 * and a refusal comes back as a refusal.
 *
 * There is no list of everyone any more: the tiles are the list, each with its own ⋯, and raising a hand is the dock's
 * own button for someone who may not send.
 */
object CallsModeration {
    /**
     * Host Controls: the room's rules for a moderator, above the dock at its end edge. Called again, it closes. The
     * rows read the room from the stage each time they are drawn ([CallsStage.people]), so `participants`, the room
     * as it was at the press, is not what they show.
     */
    fun show(stage: CallsStage, @Suppress("UNUSED_PARAMETER") participants: List<CallsRoomParticipant>) {
        val context: Context = stage.context

        // A moderator's, or anyone's who may record the call, whose Start Recording is there (the web's roomModerated).
        if (!(CallsEngine.get(context).canModerate() || stage.recording?.can == true) || stage.hostControlsAnchor == null) return

        stage.togglePopover(
            key = "room",
            anchor = { stage.hostControlsAnchor },
            side = CallsPopover.Side.ABOVE,
            align = CallsPopover.Align.STAGE_END,
            width = 340,
            room = true
        ) {
            Pair(
                CallsPopover.Head.Room(CallsIcons.shield, string(context, "host_controls", "Host Controls")),
                roomEntries(stage)
            )
        }
    }

    /**
     * One person's actions, from the ⋯ on their tile or a long-press on it, below the ⋯. Nothing opens for someone who
     * cannot moderate: the route would refuse it anyway, and a menu of actions that all fail is worse than no menu.
     */
    fun showFor(stage: CallsStage, person: CallsRoomParticipant) {
        val context: Context = stage.context

        if (!CallsEngine.get(context).canModerate() || !moderates(context, person)) return

        val identity = person.identity

        stage.togglePopover(
            key = identity,
            // Their tile wherever the grid has put it now, or the stage while they have none.
            anchor = { stage.personAnchor(identity) ?: stage },
            side = CallsPopover.Side.BELOW,
            align = CallsPopover.Align.START,
            width = 248,
            room = false
        ) {
            val now = stage.people.firstOrNull { it.identity == identity } ?: return@togglePopover null
            val entries = personEntries(context, now, stage)

            if (entries.isEmpty()) return@togglePopover null

            Pair(CallsPopover.Head.Person(now.name, now.avatar, personState(context, now)), entries)
        }
    }

    /**
     * Whether a moderator has anything to do on this tile, which is when it wears the ⋯: spotlight or pin it in a video
     * call, and for anyone else their own actions. The web's tileModerated.
     */
    fun moderates(context: Context, person: CallsRoomParticipant): Boolean {
        val engine = CallsEngine.get(context)

        if (!engine.canModerate()) return false

        return engine.isVideo || !person.isLocal
    }

    /**
     * What is on for the person now, as their menu's header says it, each a pill in its tone: a raised hand, and then
     * the first of a viewer, a microphone a moderator keeps off, and a microphone simply off.
     */
    fun personState(context: Context, person: CallsRoomParticipant): List<CallsPopover.Pill> {
        val state = mutableListOf<CallsPopover.Pill>()

        if (person.handRaised) {
            state.add(CallsPopover.Pill(string(context, "hand_raised", "Hand Raised"), CallsIcons.raiseHand, CallsPopover.Tone.WARNING))
        }

        if (!person.canPublish && !person.canAdmin) {
            state.add(
                CallsPopover.Pill(
                    if (CallsEngine.get(context).isVideo) string(context, "viewer", "Viewer") else string(context, "listener", "Listener"),
                    CallsIcons.videoOff,
                    CallsPopover.Tone.NEUTRAL
                )
            )
        } else if (person.micMuted) {
            state.add(CallsPopover.Pill(string(context, "muted_by_moderator", "Muted by a moderator"), CallsIcons.silenced, CallsPopover.Tone.DANGER))
        } else if (person.muted) {
            state.add(CallsPopover.Pill(string(context, "microphone_disabled", "Microphone Disabled"), CallsIcons.micOff, CallsPopover.Tone.NEUTRAL))
        }

        return state
    }

    /**
     * Host Controls' rows: the room's rules where the call carries them, each a switch that flips once its request
     * succeeds, the join alerts, lowering every hand while one is up, recording, the spotlight and End for Everyone.
     * The rules live in WordPress transients the join route reports back, so a moderator walking into a room somebody
     * else muted sees it muted and can let it go.
     */
    private fun roomEntries(stage: CallsStage): List<CallsPopover.Entry> {
        val context: Context = stage.context
        val people = stage.people
        val engine = CallsEngine.get(context)
        val broadcast = engine.callMode == "broadcast"

        // Whose microphones Mute Everyone mutes and lets back on: everyone else but the moderators, as the web sends
        // them.
        val others = people.filter { !it.isLocal && !it.canAdmin }.map { it.identity }

        // Someone who may record the call without moderating it has Start and Stop Recording alone.
        if (!engine.canModerate()) return recordingEntries(stage, first = true)

        val entries = mutableListOf<CallsPopover.Entry>()

        // The room's own rules, where the call carries them: an engine without them (the SDK's) offers none.
        if (engine.carriesRoomRules) entries.addAll(roomRules(context, others, broadcast))

        // A toast as someone joins, on this device: off unless turned on.
        val alerts = CallsHostPrefs.joinAlerts(context)

        entries.add(
            CallsPopover.Row(
                key = "join-alerts",
                icon = CallsIcons.userPlus,
                label = string(context, "join_alerts", "Join Alerts"),
                note = string(context, "join_alerts_note", "A toast when someone joins the call, on this device."),
                on = alerts
            ) { done ->
                CallsHostPrefs.keepJoinAlerts(context, !alerts)
                done()
            }
        )

        // Only worth offering while there is a hand to lower, which is the web's own condition.
        if (engine.carriesRoomRules && people.any { it.handRaised }) {
            entries.add(CallsPopover.Divider)
            entries.add(
                CallsPopover.Row(
                    key = "hands",
                    icon = CallsIcons.raiseHand,
                    label = string(context, "lower_all_hands", "Lower All Hands")
                ) { done ->
                    engine.groupAdmin("lower_all_hands") { done() }
                }
            )
        }

        entries.addAll(recordingEntries(stage, first = false))

        // Everyone out of the spotlight at once, while anyone is in it.
        if (engine.carriesSpotlight && engine.isVideo && CallsSpotlight.spotlit(people, stage.spotlight).isNotEmpty()) {
            entries.add(CallsPopover.Divider)
            entries.add(
                CallsPopover.Row(
                    key = "spotlight",
                    icon = CallsIcons.star,
                    label = string(context, "clear_spotlight", "Clear Spotlight"),
                    note = string(context, "clear_spotlight_note", "Everyone sees the usual tiles again."),
                ) { done -> CallsSpotlight.send(stage, null, false, done) }
            )
        }

        // Everyone taken out of the call, this side too, where the host offers it: the menu closes first, and the
        // question is asked on the call.
        if (engine.canEndForEveryone()) {
            entries.add(CallsPopover.Divider)
            entries.add(
                CallsPopover.Row(
                    key = "end",
                    icon = CallsIcons.logout,
                    label = string(context, "end_for_everyone", "End for Everyone"),
                    note = string(context, "end_for_everyone_note", "Everyone leaves the call, you too."),
                    danger = true
                ) { done ->
                    done()
                    askToEnd(stage)
                }
            )
        }

        return entries
    }

    /**
     * Mute Everyone, Lock Room and Broadcast Mode, each a switch that flips once its request succeeds, and this device's
     * sound for a raised hand.
     */
    private fun roomRules(context: Context, others: List<String>, broadcast: Boolean): List<CallsPopover.Entry> {
        val engine = CallsEngine.get(context)
        val entries = mutableListOf<CallsPopover.Entry>(
            CallsPopover.Row(
                key = "room-mute",
                icon = CallsIcons.micOff,
                label = string(context, "mute_everyone", "Mute Everyone"),
                note = string(context, "mute_everyone_note", "Microphones stay off until you allow them again. Cameras and screens keep going."),
                on = engine.roomMuted
            ) { done ->
                engine.groupAdmin(if (engine.roomMuted) "unmute_all" else "mute_all", identities = others) { done() }
            },
            CallsPopover.Row(
                key = "room-lock",
                icon = CallsIcons.padlock,
                label = string(context, "lock_room", "Lock Room"),
                note = string(context, "lock_room_note", "Nobody else can join."),
                on = engine.roomLocked
            ) { done ->
                engine.groupAdmin(if (engine.roomLocked) "unlock_room" else "lock_room") { done() }
            },
            CallsPopover.Row(
                key = "mode",
                icon = CallsIcons.megaphone,
                label = string(context, "broadcast_mode_rule", "Broadcast Mode"),
                note = string(context, "broadcast_mode_note", "Only speakers can turn on a camera or microphone. Everyone else watches and can raise a hand."),
                on = broadcast
            ) { done ->
                engine.groupAdmin(if (broadcast) "set_participatory_mode" else "set_broadcast_mode") { done() }
            },
        )

        // A sound for a raised hand, on this device, offered in every mode as the web's Host Controls offers it: a hand
        // can go up in either.
        val handSound = CallsHostPrefs.handSound(context)

        entries.add(
            CallsPopover.Row(
                key = "hand-sound",
                icon = CallsIcons.bell,
                label = string(context, "hand_sound", "Sound for Raised Hands"),
                note = string(context, "hand_sound_note", "A short sound when someone raises a hand, on this device."),
                on = handSound
            ) { done ->
                CallsHostPrefs.keepHandSound(context, !handSound)
                done()
            }
        )

        return entries
    }

    /**
     * Start Recording, or Stop Recording while it records, for whoever may record the call (the web's room panel): an act,
     * not a rule of the room, asked first on the call once the menu has closed ([CallsStage.askRecording]). Start says how
     * everyone else will be told; a press does nothing while it starts or saves.
     */
    private fun recordingEntries(stage: CallsStage, first: Boolean): List<CallsPopover.Entry> {
        val context: Context = stage.context
        val recording = stage.recording?.takeIf { it.can } ?: return emptyList()
        val say: (String, String) -> String = { key, fallback -> string(context, key, fallback) }
        val stop = recording.running

        val row = CallsPopover.Row(
            key = "record",
            icon = CallsIcons.record,
            label = if (stop) say("rec_stop_recording", "Stop Recording") else say("rec_start", "Start Recording"),
            note = if (stop) say("rec_stop_note", "Asks you first. It is ready to watch a few minutes later.") else recording.startNote(say),
            danger = stop
        ) { done ->
            done()
            stage.closePopover()

            // After the row's own press has finished drawing.
            stage.post { stage.askRecording(if (stop) "stop" else "start") }
        }

        return if (first) listOf(row) else listOf(CallsPopover.Divider, row)
    }

    /** End for Everyone's question, on the call; yes ends it for everyone through the engine. */
    fun askToEnd(stage: CallsStage) {
        val context = stage.context

        stage.closePopover()
        stage.confirm(
            title = string(context, "end_for_everyone_question", "End the call for everyone?"),
            message = string(context, "end_for_everyone_note", "Everyone leaves the call, you too."),
            answer = string(context, "end_for_everyone", "End for Everyone"),
            danger = true
        ) {
            CallsEngine.get(context).endForEveryone { }
        }
    }

    /**
     * Spotlight for Everyone, or why it can't be done now: with four people in the spotlight it is dimmed, its note
     * saying so, as the web's row is.
     */
    private fun spotlightRow(context: Context, full: Boolean, run: (done: () -> Unit) -> Unit) = CallsPopover.Row(
        key = "spot",
        icon = CallsIcons.star,
        label = string(context, "spotlight_for_everyone", "Spotlight for Everyone"),
        note = if (full) {
            string(context, "spotlight_full", CallsSpotlight.FULL)
        } else {
            string(context, "spotlight_note", "Their tile is the main one for everyone in the call.")
        },
        disabled = full,
        run = run,
    )

    /** Pin for Me, this user's own and anyone's, where [CallsSpotlight.pinOffered] offers it. */
    private fun pinForMeRow(context: Context, identity: String, stage: CallsStage): CallsPopover.Row {
        val pinned = identity in stage.pinnedForMe

        return CallsPopover.Row(
            key = "pin-for-me",
            icon = if (pinned) CallsIcons.pinOff else CallsIcons.pin,
            label = if (pinned) string(context, "unpin_for_me", "Unpin for Me") else string(context, "pin_for_me", "Pin for Me"),
        ) { done ->
            stage.togglePinForMe(identity)
            done()
        }
    }

    /**
     * The web's person menu, in the order and on the conditions it draws it: in a video call the spotlight for everyone
     * (or a call's pin for everyone) and Pin for Me, and for everybody else their microphone, whether they may send at
     * all (a viewer or a speaker in broadcast mode, their camera and microphone outside it), their raised hand, and
     * removing them.
     */
    private fun personEntries(context: Context, person: CallsRoomParticipant, stage: CallsStage): List<CallsPopover.Entry> {
        val engine = CallsEngine.get(context)
        val broadcast = engine.callMode == "broadcast"
        val video = engine.isVideo
        val identity = person.identity

        fun act(action: String): (() -> Unit) -> Unit = { done -> engine.groupAdmin(action, identity) { done() } }

        fun row(key: String, icon: String, words: String, fallback: String, action: String, highlight: Boolean = false, note: Pair<String, String>? = null) =
            CallsPopover.Row(
                key = key, icon = icon, label = string(context, words, fallback),
                note = note?.let { string(context, it.first, it.second) }, highlight = highlight, run = act(action),
            )

        // Whether they send anything a stop would take: their microphone, camera or screen.
        val sending = !person.muted || person.video != null || person.screen != null

        val entries = mutableListOf<CallsPopover.Entry>()

        // Asking them to turn on what is off ([CallsAsks]), first as on the web: their microphone, unless a moderator
        // holds it off, and on a video call their camera, both in one row while both are, each in its own once one is on
        // or while one was asked for alone a moment ago. "Asked…" for a while after, with their answer under it.
        if (engine.carriesAsks && !person.isLocal && person.canPublish) {
            val microphone = person.muted && !person.micMuted
            val camera = video && person.video == null
            val both = microphone && camera && CallsAsks.isAsked(identity, "camera") == CallsAsks.isAsked(identity, "microphone")
            val asks = if (both) {
                listOf(listOf("camera", "microphone"))
            } else {
                listOfNotNull(listOf("microphone").takeIf { microphone }, listOf("camera").takeIf { camera })
            }

            for (devices in asks) {
                val asked = devices.all { CallsAsks.isAsked(identity, it) }
                val label = when {
                    asked -> string(context, "ask_asked", "Asked…")
                    devices.size > 1 -> string(context, "ask_turn_on_both", "Ask to Turn On Camera and Microphone")
                    devices[0] == "camera" -> string(context, "ask_turn_on_camera", "Ask to Turn On Camera")
                    else -> string(context, "ask_turn_on_microphone", "Ask to Turn On Microphone")
                }

                entries.add(
                    CallsPopover.Row(
                        key = "ask-" + devices.joinToString("-"),
                        icon = if ("camera" in devices) CallsIcons.video else CallsIcons.mic,
                        label = label,
                        note = if (asked) CallsAsks.answered(context, identity, devices, CallsHostToasts.plain(person.name)) else null,
                        run = { done ->
                            if (asked) {
                                done()
                            } else {
                                val after = CallsAsks.answers.size
                                val action = if (devices.size > 1) "ask_camera_microphone" else "ask_" + devices[0]

                                engine.groupAdmin(action, identity) { ok ->
                                    if (ok) {
                                        CallsAsks.record(identity, devices, after)
                                        // "Asked…" runs out: the menu says what it offers again.
                                        stage.postDelayed({ stage.refreshPopover() }, CallsAsks.ASKED_MILLIS + 100)
                                    }

                                    done()
                                    stage.refreshPopover()
                                }
                            }
                        },
                    )
                )
            }
        }

        // An audio roster pins and spotlights nobody: it has no main tiles. Spotlight for Everyone where the call carries
        // it, in place of the pin for everyone of before, which a call without a spotlight keeps; and the moderator's own
        // Pin for Me ([CallsSpotlight]).
        if (video) {
            val spotlit = CallsSpotlight.spotlit(stage.people, stage.spotlight)

            entries.add(
                when {
                    !engine.carriesSpotlight && person.isPinned ->
                        row("pin", CallsIcons.pinOff, "unpin_participant", "Unpin Participant", "unpin_participant")
                    !engine.carriesSpotlight ->
                        row("pin", CallsIcons.pin, "pin_participant", "Pin Participant", "pin_participant")
                    identity in spotlit -> CallsPopover.Row(
                        key = "spot",
                        icon = CallsIcons.close,
                        label = string(context, "remove_spotlight", "Remove Spotlight"),
                    ) { done -> CallsSpotlight.send(stage, identity, false, done) }
                    else -> spotlightRow(context, full = spotlit.size >= CallsSpotlight.MOST) { done ->
                        CallsSpotlight.send(stage, identity, true, done)
                    }
                }
            )

            if (CallsSpotlight.pinOffered(identity, stage.pinnedForMe, spotlit)) entries.add(pinForMeRow(context, identity, stage))
        }

        if (person.isLocal) return entries

        // `micMuted` is the moderator's own doing, which Allow Microphone undoes. Somebody who simply has not switched
        // their microphone on is not muted by anyone: a mute holds it off until allowed, and where it is off already
        // the row says that, as "Mute Microphone" reads as if it were on.
        if (person.canPublish) {
            entries.add(
                when {
                    person.micMuted -> row("mic", CallsIcons.mic, "allow_microphone", "Allow Microphone", "unmute_mic")
                    person.muted -> row(
                        "mic", CallsIcons.micOff, "keep_microphone_off", "Keep Microphone Off", "mute_mic",
                        note = "keep_microphone_off_note" to "They cannot turn it on until you allow it.",
                    )
                    else -> row("mic", CallsIcons.micOff, "mute_microphone", "Mute Microphone", "mute_mic")
                }
            )
        }

        if (broadcast) {
            // In broadcast mode by promoting and demoting.
            if (!person.canAdmin) {
                entries.add(
                    if (person.canPublish) {
                        row("speaker", CallsIcons.arrowDown, "demote_to_viewer", "Demote to Viewer", "demote_participant")
                    } else {
                        row("speaker", CallsIcons.arrowUp, "promote_to_speaker", "Promote to Speaker", "promote_participant", highlight = person.handRaised)
                    }
                )
            }
        } else if (person.canPublish) {
            // Outside it, on a video call, said as what it does to them: their camera and microphone (and screen)
            // stopped, or kept off where none is on, or let back on. An audio call has no camera to name, and keeps
            // viewer and speaker.
            entries.add(
                when {
                    !video -> row("speaker", CallsIcons.arrowDown, "demote_to_viewer", "Demote to Viewer", "mute_participant")
                    sending -> row("speaker", CallsIcons.videoOff, "stop_camera_mic", "Stop Camera & Mic", "mute_participant")
                    else -> row(
                        "speaker", CallsIcons.videoOff, "keep_camera_mic_off", "Keep Camera & Mic Off", "mute_participant",
                        note = "keep_camera_mic_off_note" to "They cannot turn them on until you allow them.",
                    )
                }
            )
        } else {
            entries.add(
                if (video) {
                    row("speaker", CallsIcons.video, "allow_camera_mic", "Allow Camera & Mic", "unmute_participant", highlight = person.handRaised)
                } else {
                    row("speaker", CallsIcons.arrowUp, "promote_to_speaker", "Promote to Speaker", "unmute_participant", highlight = person.handRaised)
                }
            )
        }

        if (person.handRaised) {
            entries.add(row("hand", CallsIcons.raiseHand, "lower_hand", "Lower Hand", "lower_hand"))
        }

        entries.add(CallsPopover.Divider)
        entries.add(
            CallsPopover.Row(
                key = "kick",
                icon = CallsIcons.userX,
                label = string(context, "remove_from_call", "Remove from Call"),
                danger = true,
                run = act("remove_participant")
            )
        )

        return entries
    }

    private fun string(context: Context, key: String, fallback: String): String =
        CallsTheme.string(context, key, fallback)
}

/**
 * Spotlight for Everyone and Pin for Me, as the web's calls/spotlight.tsx has them. A moderator puts up to four people on
 * the main tiles for everyone in a group video call: the room's state (the engine's state's `spotlight`), so it outlives
 * a reconnect and whoever joins later sees it, each such tile labeled Spotlight. Anyone pins someone for themselves (Pin
 * for Me, this phone alone), which shows while nobody is in the spotlight: a spotlight comes first, and the pins wait for
 * it to end. A moderator's pin from an older app (the person's `isPinned`) is a spotlight too, as everyone already saw it
 * as one.
 */
object CallsSpotlight {
    /** How many can be in the spotlight at once, as the call's server keeps it. */
    const val MOST = 4

    /** A fifth person refused, the call's own words in English. */
    const val FULL = "Up to four people can be in the spotlight."

    /**
     * Who is in the spotlight, by identity, in the order they were spotlighted: the room's spotlight for those in the call
     * now, then anyone an older app pinned for everyone.
     */
    fun spotlit(people: List<CallsRoomParticipant>, spotlight: List<String>): List<String> {
        val spotlit = mutableListOf<String>()

        for (identity in spotlight) {
            if (identity !in spotlit && people.any { it.identity == identity }) spotlit.add(identity)
        }

        for (person in people) {
            if (person.isPinned && person.identity !in spotlit) spotlit.add(person.identity)
        }

        return spotlit
    }

    /**
     * The main tiles, by identity, in their order: the spotlight while anyone is in it, else this user's own pins, else in
     * broadcast mode the speakers, as the web's mainKeys. A pin beside the speakers made it one main tile of the same size
     * among them, so pinning a speaker changed nothing on screen.
     */
    fun main(people: List<CallsRoomParticipant>, spotlight: List<String>, pinned: List<String>, broadcast: Boolean): List<String> {
        val spotlit = spotlit(people, spotlight)

        if (spotlit.isNotEmpty()) return spotlit

        val main = pinned.filter { identity -> people.any { it.identity == identity } }.toMutableList()

        if (main.isNotEmpty()) return main

        if (broadcast) {
            for (person in people) {
                if ((person.canPublish || person.canAdmin) && person.identity !in main) main.add(person.identity)
            }
        }

        return main
    }

    /**
     * Whether Pin for Me is offered for someone, as the web's usePinOffered: while nobody is in the spotlight, which comes
     * first and would keep the pin waiting, and for a tile already pinned in any case, so its Unpin for Me stays.
     */
    fun pinOffered(identity: String, pinned: List<String>, spotlit: List<String>): Boolean =
        spotlit.isEmpty() || identity in pinned

    /** Pin for Me's pins once the people changed: someone who leaves is unpinned, so coming back they are a tile like the others. */
    fun kept(pinned: List<String>, people: List<CallsRoomParticipant>): List<String> =
        pinned.filter { identity -> people.any { it.identity == identity } }

    /** A pin put on or taken off, the others kept in the order they were pinned. */
    fun toggled(pinned: List<String>, identity: String): List<String> =
        if (identity in pinned) pinned.filter { it != identity } else pinned + identity

    /**
     * A moderator's spotlight act through the engine, `done` once it is answered. A refusal is shown on the call, the
     * route's own words where it gave some (a fifth person: "Up to four people can be in the spotlight.").
     */
    fun send(stage: CallsStage, identity: String?, on: Boolean, done: () -> Unit) {
        val context = stage.context

        CallsEngine.get(context).spotlight(identity, on) { ok, error ->
            done()

            if (!ok) {
                stage.post {
                    stage.onScreenError(
                        error ?: CallsTheme.string(context, "spotlight_failed", "The call server could not do this. Please try again.")
                    )
                }
            }
        }
    }
}

/**
 * A moderator's two switches for this device, under the web's own keys: Sound for Raised Hands, on unless turned off
 * ("0"), and Join Alerts, off unless turned on ("1").
 */
object CallsHostPrefs {
    const val HAND_SOUND_KEY = "wordplus-call-hand-sound"
    const val JOIN_ALERTS_KEY = "wordplus-call-join-alerts"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("wordplus-calls", Context.MODE_PRIVATE)

    fun handSound(context: Context): Boolean = prefs(context).getString(HAND_SOUND_KEY, null) != "0"

    fun keepHandSound(context: Context, on: Boolean) {
        prefs(context).edit().putString(HAND_SOUND_KEY, if (on) "1" else "0").apply()
    }

    fun joinAlerts(context: Context): Boolean = prefs(context).getString(JOIN_ALERTS_KEY, null) == "1"

    fun keepJoinAlerts(context: Context, on: Boolean) {
        prefs(context).edit().putString(JOIN_ALERTS_KEY, if (on) "1" else "0").apply()
    }
}

/**
 * What a moderator is told of the people in the call, as the web's useHostToasts tells it: a raised hand, with its
 * chime unless Sound for Raised Hands is off, and who joined where Join Alerts is on. Who was there when the moderator
 * came in is not news, nor is this side's reconnect, nor a tile back from reconnecting (it never left the roster). A
 * hand again within a minute of its toast, or someone back within two minutes of leaving, is not news again. Each
 * person is in one toast at most: a new one about them takes them out of the one before, a lowered hand takes them off
 * a hand's toast and a departure off any. Joins within three seconds share a toast. Three stand at most, newest first,
 * in the stage's top end corner, each with its ×; the rest of the corner takes no touches.
 */
class CallsHostToasts(context: android.content.Context) : android.widget.FrameLayout(context) {
    private class Person(val key: String, val name: String, val avatar: String)

    /** For `answer`: what the person asked did, and whether with both devices, asked for both at once. */
    private class Toast(val id: Int, val kind: String, val people: List<Person>, val answer: CallsTurnOnAnswer? = null, val both: Boolean = false)

    private var answersHeard: Int? = null

    /**
     * The answers to this moderator's asks ([CallsAsks]), each once, from the second reading on: the two answers to an
     * ask for both, alike, are one toast.
     */
    fun heard(answers: List<CallsTurnOnAnswer>, people: List<CallsRoomParticipant>) {
        val before = answersHeard

        answersHeard = answers.size

        if (before == null || before > answers.size) return

        val fresh = answers.drop(before)

        if (fresh.isEmpty()) return

        val next = shown.toMutableList()

        for (answer in fresh) {
            val found = people.firstOrNull { it.identity == answer.from } ?: continue
            val pair = next.indexOfFirst { toast ->
                val other = toast.answer
                toast.kind == "answer" && !toast.both && other != null && other.id == answer.id && other.from == answer.from &&
                    other.answer == answer.answer && other.device != answer.device
            }

            if (pair >= 0) {
                val was = next[pair]
                next[pair] = Toast(was.id, was.kind, was.people, was.answer, both = true)
                expireIn(was.id, ANSWER_TOAST_MS)
                continue
            }

            val toast = Toast(nextId++, "answer", listOf(Person(found.identity, plain(found.name), found.avatar)), answer)
            next.add(toast)
            expireIn(toast.id, ANSWER_TOAST_MS)
        }

        show(next.takeLast(MOST_TOASTS))
    }

    private val density = resources.displayMetrics.density
    private val column = android.widget.LinearLayout(context)

    private var shown: List<Toast> = emptyList()
    private var seen: Map<String, Boolean>? = null
    private val leftAt = HashMap<String, Long>()
    private val handAt = HashMap<String, Long>()
    private var nextId = 1
    private var lastJoin: Pair<Int, Long>? = null
    private val timers = HashMap<Int, Runnable>()
    private var drawn = ""

    private fun dp(value: Int): Int = (value * density).toInt()

    init {
        column.orientation = android.widget.LinearLayout.VERTICAL
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE

        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            marginEnd = dp(14)
            topMargin = dp(14)
        })
    }

    /** `top`: how far down the stage they start, under the system bars and the call's header. */
    fun place(top: Int) {
        val params = column.layoutParams as LayoutParams

        if (params.topMargin != top) {
            params.topMargin = top
            column.layoutParams = params
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)

        (column.layoutParams as LayoutParams).width = minOf(dp(320), width - dp(28)).coerceAtLeast(0)

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The room as it is now: news only while `connected`, and only for a `moderator`. */
    fun update(people: List<CallsRoomParticipant>, connected: Boolean, moderator: Boolean) {
        if (!moderator) {
            seen = null
            show(emptyList())
            return
        }

        if (!connected) {
            seen = null
            return
        }

        val before = seen
        val now = LinkedHashMap<String, Boolean>()

        for (person in people) if (!person.isLocal) now[person.identity] = person.handRaised

        seen = now

        if (before == null) return

        val at = System.currentTimeMillis()
        val left = mutableSetOf<String>()
        val lowered = mutableSetOf<String>()

        for ((key, hand) in before) {
            if (!now.containsKey(key)) {
                left.add(key)
                leftAt[key] = at
            } else if (hand && now[key] != true) {
                lowered.add(key)
            }
        }

        val joined = mutableListOf<String>()
        val hands = mutableListOf<String>()
        val alerts = CallsHostPrefs.joinAlerts(context)

        for ((key, hand) in now) {
            if (!before.containsKey(key)) {
                val gone = leftAt.remove(key)

                if (alerts && !(gone != null && at - gone < REJOIN_MS)) joined.add(key)
            }

            if (hand && before[key] != true) {
                val last = handAt[key]

                if (last != null && at - last < HAND_AGAIN_MS) continue

                handAt[key] = at
                hands.add(key)
            }
        }

        if (left.isEmpty() && lowered.isEmpty() && joined.isEmpty() && hands.isEmpty()) return

        fun person(key: String): Person {
            val participant = people.first { it.identity == key }

            return Person(key, plain(participant.name), participant.avatar)
        }

        val raised = hands.toSet()
        var next = shown
            .map { toast ->
                val kept = toast.people.filter { it.key !in left && it.key !in raised && !(toast.kind == "hand" && it.key in lowered) }

                if (kept.size == toast.people.size) toast else Toast(toast.id, toast.kind, kept, toast.answer, toast.both)
            }
            .filter { it.people.isNotEmpty() }

        if (joined.isNotEmpty()) {
            val last = lastJoin
            val open = if (last != null && at - last.second < JOINS_TOGETHER_MS) next.firstOrNull { it.id == last.first } else null

            if (open != null) {
                next = next.map { if (it === open) Toast(it.id, it.kind, it.people + joined.map(::person)) else it }
                lastJoin = open.id to at
                expireIn(open.id, JOIN_TOAST_MS)
            } else {
                val toast = Toast(nextId++, "join", joined.map(::person))

                next = next + toast
                lastJoin = toast.id to at
                expireIn(toast.id, JOIN_TOAST_MS)
            }
        }

        for (key in hands) {
            val toast = Toast(nextId++, "hand", listOf(person(key)))

            next = next + toast
            expireIn(toast.id, HAND_TOAST_MS)
        }

        show(next.takeLast(MOST_TOASTS))

        if (hands.isNotEmpty() && CallsHostPrefs.handSound(context)) CallsTone.hand()
    }

    private fun dismiss(id: Int) = show(shown.filter { it.id != id })

    private fun expireIn(id: Int, millis: Long) {
        timers.remove(id)?.let { removeCallbacks(it) }

        val away = Runnable { dismiss(id) }

        timers[id] = away
        postDelayed(away, millis)
    }

    private fun show(next: List<Toast>) {
        for (toast in shown) {
            if (next.none { it.id == toast.id }) timers.remove(toast.id)?.let { removeCallbacks(it) }
        }

        shown = next

        val said = next.joinToString("\n") { toast -> "${toast.id}|${toast.kind}|${toast.both}|" + toast.people.joinToString(",") { it.key } }

        if (said == drawn) return

        val before = drawn.lines().mapNotNull { it.substringBefore('|').toIntOrNull() }.toSet()

        drawn = said
        column.removeAllViews()

        // Newest first, from the top.
        for ((index, toast) in next.reversed().withIndex()) {
            val view = toastView(toast)

            column.addView(view, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = dp(8) })

            // In from above, as the web's; only a toast that is new.
            if (toast.id !in before) {
                view.alpha = 0f
                view.translationY = -4 * density
                view.animate().alpha(1f).translationY(0f).setDuration(180).start()
            }
        }
    }

    private fun text(toast: Toast): String {
        val first = toast.people[0].name

        if (toast.kind == "hand") return CallsTheme.string(context, "hand_raised_toast", "%s raised a hand").replace("%s", first)

        toast.answer?.takeIf { toast.kind == "answer" }?.let { return CallsAsks.text(context, it, first, toast.both) }

        return when (toast.people.size) {
            1 -> CallsTheme.string(context, "joined_toast", "%s joined").replace("%s", first)
            2 -> CallsTheme.string(context, "joined_two_toast", "%1\$s and %2\$s joined")
                .replace("%1\$s", first).replace("%2\$s", toast.people[1].name)
            else -> CallsTheme.string(context, "joined_others_toast", "%1\$s and %2\$d others joined")
                .replace("%1\$s", first).replace("%2\$d", (toast.people.size - 1).toString())
        }
    }

    private fun toastView(toast: Toast): android.view.View {
        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPaddingRelative(dp(8), dp(6), dp(6), dp(6))
            // The call's glass, more solid than the web's 0.75: the web blurs what lies behind it, and a tile's letter
            // read through this one.
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(android.graphics.Color.argb(230, 22, 24, 30))
                setStroke(maxOf(1, dp(1)), android.graphics.Color.argb(20, 255, 255, 255))
            }
            // Its own touches, so a press on it does not reach the tile under it.
            isClickable = true
        }

        val badge = android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (toast.kind == "hand") CallsTheme.warning(context) else android.graphics.Color.argb(31, 255, 255, 255))
            }
            clipToOutline = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        if (toast.kind == "hand") {
            badge.addView(CallsIconView(context).apply {
                set(CallsIcons.raiseHand)
                glyphColor = android.graphics.Color.rgb(17, 19, 24)
            }, LayoutParams(dp(16), dp(16), android.view.Gravity.CENTER))
        } else {
            val first = toast.people[0]
            val image = android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
            val initial = android.widget.TextView(context).apply {
                text = first.name.firstOrNull()?.uppercase() ?: ""
                textSize = 12f
                typeface = CallsTheme.SEMIBOLD
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
            }

            badge.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            badge.addView(initial, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            if (first.avatar.isNotEmpty()) {
                CallsAvatars.load(context, first.avatar) { bitmap ->
                    if (bitmap == null) return@load

                    image.setImageBitmap(bitmap)
                    initial.visibility = android.view.View.GONE
                }
            }
        }

        row.addView(badge, android.widget.LinearLayout.LayoutParams(dp(28), dp(28)))

        row.addView(android.widget.TextView(context).apply {
            text = text(toast)
            textSize = 13f
            typeface = CallsTheme.MEDIUM
            setTextColor(android.graphics.Color.WHITE)
            setLineSpacing(0f, 1.1f)
        }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })

        row.addView(android.widget.FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = CallsTheme.string(context, "dismiss", "Dismiss")
            addView(CallsIconView(context).apply {
                set(CallsIcons.close)
                glyphColor = android.graphics.Color.argb(179, 255, 255, 255)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(14), dp(14), android.view.Gravity.CENTER))
            setOnClickListener { dismiss(toast.id) }
        }, android.widget.LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(10) })

        return row
    }

    override fun onDetachedFromWindow() {
        timers.values.forEach { removeCallbacks(it) }
        timers.clear()
        super.onDetachedFromWindow()
    }

    companion object {
        const val HAND_TOAST_MS = 10_000L
        const val JOIN_TOAST_MS = 5_000L
        const val ANSWER_TOAST_MS = 8_000L
        const val JOINS_TOGETHER_MS = 3_000L
        const val MOST_TOASTS = 3
        const val HAND_AGAIN_MS = 60_000L
        const val REJOIN_MS = 120_000L

        /** A name as text: a site's badges in it are markup the tiles draw, and a toast has no room for them. */
        fun plain(name: String): String = name.replace(Regex("<[^>]*>"), "").trim()
    }
}

/** A moderator's ask that this user turn on a device, as the engine's state names it (`asks`). */
data class CallsTurnOnAsk(val id: String, val device: String, val by: String)

/** An answer to one of this user's own asks (`askAnswers`): [from] is whom they asked. */
data class CallsTurnOnAnswer(val id: String, val from: String, val device: String, val answer: String)

/**
 * A moderator asking someone to turn on their camera or microphone, as the web asks (realtime-ui's ask-prompt.tsx), where
 * the engine carries asks (`CallsEngine.carriesAsks`): the moderator's rows in a person's menu ([CallsModeration]), one
 * for both while both are off, "Asked…" for [ASKED_MILLIS] after with the answer under it, and a toast of the answer
 * ([CallsHostToasts]); the person's card over the dock ([CallsAskCard]), whose Turn On does what the dock's buttons do,
 * the system asked once for whatever it has not allowed. Nothing is turned on without their press.
 */
object CallsAsks {
    const val ASKED_MILLIS = 30_000L

    /** Why a device would not turn on: the system refuses it to the app, or there is none. */
    enum class Stuck(val answer: String) { BLOCKED("blocked"), NO_DEVICE("no_device") }

    /**
     * The asks this device sent, by person and device, for [ASKED_MILLIS]: `after` is how many answers it had heard as it
     * asked, so the menu says the answer to this ask and to none before it.
     */
    private val asked = HashMap<String, Pair<Long, Int>>()

    /** The answers to this user's own asks, as the screen last heard them. */
    var answers: List<CallsTurnOnAnswer> = emptyList()

    /** The answer of the system to the permissions Turn On asked for, told to the card waiting on it. */
    var onPermissions: (() -> Unit)? = null

    fun record(identity: String, devices: List<String>, after: Int) {
        val at = android.os.SystemClock.elapsedRealtime()

        for (device in devices) asked["$identity|$device"] = at to after
    }

    fun isAsked(identity: String, device: String): Boolean {
        val sent = asked["$identity|$device"] ?: return false

        return android.os.SystemClock.elapsedRealtime() - sent.first < ASKED_MILLIS
    }

    /**
     * The answer to this device's ask for [devices] while it says "Asked…", as the toast says it: one line where both
     * answered alike, else each that came.
     */
    fun answered(context: android.content.Context, identity: String, devices: List<String>, name: String): String? {
        val heard = devices.mapNotNull { device ->
            val sent = asked["$identity|$device"]?.takeIf { isAsked(identity, device) } ?: return@mapNotNull null

            answers.drop(sent.second).lastOrNull { it.from == identity && it.device == device }
        }

        if (heard.size == 2 && heard[0].answer == heard[1].answer) return text(context, heard[0], name, both = true)

        return heard.takeIf { it.isNotEmpty() }?.joinToString(" · ") { text(context, it, name, both = false) }
    }

    /** What the moderator is told of an answer, [name] being whom they asked; [both] for both devices answered alike. */
    fun text(context: android.content.Context, answer: CallsTurnOnAnswer, name: String, both: Boolean): String {
        val camera = answer.device == "camera"
        val words = when (answer.answer) {
            "on" -> when {
                both -> CallsTheme.string(context, "ask_answer_on_both", "%s turned on their camera and microphone")
                camera -> CallsTheme.string(context, "ask_answer_on_camera", "%s turned on their camera")
                else -> CallsTheme.string(context, "ask_answer_on_microphone", "%s turned on their microphone")
            }
            "not_now" -> when {
                both -> CallsTheme.string(context, "ask_answer_not_now_both", "%s is not turning on their camera and microphone now")
                camera -> CallsTheme.string(context, "ask_answer_not_now_camera", "%s is not turning on their camera now")
                else -> CallsTheme.string(context, "ask_answer_not_now_microphone", "%s is not turning on their microphone now")
            }
            "blocked" -> when {
                both -> CallsTheme.string(context, "ask_answer_blocked_both", "%s: their browser blocks the camera and microphone")
                camera -> CallsTheme.string(context, "ask_answer_blocked_camera", "%s: their browser blocks the camera")
                else -> CallsTheme.string(context, "ask_answer_blocked_microphone", "%s: their browser blocks the microphone")
            }
            "no_device" -> when {
                both -> CallsTheme.string(context, "ask_answer_no_device_both", "%s: no camera or microphone found")
                camera -> CallsTheme.string(context, "ask_answer_no_device_camera", "%s: no camera found")
                else -> CallsTheme.string(context, "ask_answer_no_device_microphone", "%s: no microphone found")
            }
            else -> when {
                both -> CallsTheme.string(context, "ask_answer_busy_both", "%s: another app is using their camera and microphone")
                camera -> CallsTheme.string(context, "ask_answer_busy_camera", "%s: another app is using their camera")
                else -> CallsTheme.string(context, "ask_answer_busy_microphone", "%s: another app is using their microphone")
            }
        }

        return words.replace("%s", name)
    }

    /** Whether the device has one at all: a tablet without a camera has nothing to turn on. */
    fun hasDevice(context: android.content.Context, device: String): Boolean = context.packageManager.hasSystemFeature(
        if (device == "camera") android.content.pm.PackageManager.FEATURE_CAMERA_ANY else android.content.pm.PackageManager.FEATURE_MICROPHONE
    )

    fun granted(context: android.content.Context, device: String): Boolean =
        if (device == "camera") CallsPermissions.cameraGranted(context) else CallsPermissions.microphoneGranted(context)

    fun permission(device: String): String =
        if (device == "camera") android.Manifest.permission.CAMERA else android.Manifest.permission.RECORD_AUDIO

    /** What the card says of a device that would not turn on, and what to do about it. */
    fun stuckTitle(context: android.content.Context, device: String, stuck: Stuck): String {
        val camera = device == "camera"

        return when (stuck) {
            Stuck.BLOCKED -> if (camera) {
                CallsTheme.string(context, "ask_blocked_camera", "The camera is not allowed for this app")
            } else {
                CallsTheme.string(context, "ask_blocked_microphone", "The microphone is not allowed for this app")
            }
            Stuck.NO_DEVICE -> if (camera) {
                CallsTheme.string(context, "ask_no_camera", "No camera found")
            } else {
                CallsTheme.string(context, "ask_no_microphone", "No microphone found")
            }
        }
    }

    fun stuckSteps(context: android.content.Context, device: String, stuck: Stuck): List<String> = when (stuck) {
        Stuck.BLOCKED -> {
            val label = if (device == "camera") {
                CallsTheme.string(context, "media_camera", "Camera")
            } else {
                CallsTheme.string(context, "media_microphone", "Microphone")
            }

            listOf(CallsTheme.string(context, "ask_allow_steps", "Open Settings, allow %s for this app, then come back and press Try Again.").replace("%s", label))
        }
        Stuck.NO_DEVICE -> listOf(CallsTheme.string(context, "ask_connect_steps", "Connect one, or check that it is switched on, then press Try Again."))
    }
}

/**
 * A group call's recording, as the engine's state names it (`recording`) and as the web's call screens draw it
 * (realtime-ui's recording.tsx): REC in the bar, the notice over the dock, the question to stay or leave, and a
 * moderator's Start and Stop with their confirms. The engine keeps this user's answer by the recording's id and holds the
 * microphone, camera and screen off while the site asks for that and the answer is not `agreed` (`held`); the screens
 * draw from what it says. Nothing here sends anything. Its words come through `say` (a key and its English), which the
 * stage answers from the site's strings.
 */
data class CallsRecording(
    /** `off`, `starting`, `on` or `stopping`. */
    val state: String = "off",
    /** The recording's own id, which the answers are kept by: "" for none. */
    val id: String = "",
    /** When it started, in seconds since 1970: 0 for not known. */
    val since: Long = 0,
    /** Whether this user may start and stop it. */
    val can: Boolean = false,
    /** `indicator`, `moderators`, `notice`, `ask` or `muted`. */
    val tell: String = "notice",
    /** Who started it, by name. */
    val by: String = "",
    /** This user started it: they are told and asked nothing. */
    val mine: Boolean = false,
    /** The user's own choice about the notice: false after Do Not Show Again. */
    val notice: Boolean = true,
    /** This user's answer to it: `agreed`, `seen`, or "". */
    val answer: String = "",
    /** The engine holds the microphone, camera and screen off until they agree. */
    val held: Boolean = false,
    val size: Int = 0,
    val layout: String = "",
    val audioLayout: String = "",
    val transcript: Boolean? = null,
    val language: String = "",
    val watch: String = "",
    val keepDays: Int? = null,
    /** The host's words for the confirm in the site's language: the days it is kept, and the language's name. */
    val keptWords: String = "",
    val languageWords: String = "",
) {
    companion object {
        /** The state's `recording`: null where there is none. */
        fun of(value: org.json.JSONObject?): CallsRecording? {
            value ?: return null

            val settings = value.optJSONObject("settings")
            val words = value.optJSONObject("words")
            val id = value.opt("id")

            return CallsRecording(
                state = stateOf(value.optString("state")),
                id = when (id) {
                    is String -> id
                    is Number -> if (id.toLong() != 0L) id.toLong().toString() else ""
                    else -> ""
                },
                since = value.optLong("since", 0),
                can = value.optBoolean("can", false),
                tell = tellOf(value.optString("tell")),
                by = value.optString("by"),
                mine = value.optBoolean("mine", false),
                notice = value.optBoolean("notice", true),
                answer = value.optString("answer"),
                held = value.optBoolean("held", false),
                size = settings?.optInt("size", 0) ?: 0,
                layout = settings?.optString("layout") ?: "",
                audioLayout = settings?.optString("audioLayout") ?: "",
                transcript = settings?.takeIf { it.has("transcript") }?.optBoolean("transcript"),
                language = settings?.optString("language") ?: "",
                watch = settings?.optString("watch") ?: "",
                keepDays = settings?.takeIf { it.has("keepDays") }?.optInt("keepDays"),
                keptWords = words?.optString("kept") ?: "",
                languageWords = words?.optString("language") ?: "",
            )
        }

        /** The store's states as the call screen knows them (recording.ts's stateOf). */
        fun stateOf(value: String): String = when (value) {
            "starting" -> "starting"
            "recording", "on" -> "on"
            "stopping" -> "stopping"
            else -> "off"
        }

        fun tellOf(value: String): String = if (value in listOf("indicator", "moderators", "ask", "muted")) value else "notice"

        /** m:ss, h:mm:ss past an hour. */
        fun elapsed(seconds: Long): String {
            val s = maxOf(0L, seconds)
            val hours = s / 3600
            val minutes = (s % 3600) / 60
            val rest = String.format(java.util.Locale.ROOT, "%02d", s % 60)

            return if (hours > 0) "$hours:" + String.format(java.util.Locale.ROOT, "%02d", minutes) + ":" + rest else "$minutes:$rest"
        }
    }

    /** It runs, or is on its way to. */
    val running: Boolean get() = state == "on" || state == "starting"

    /** REC shows at all: not where moderators alone know of it and this user is none. */
    val pillShown: Boolean get() = !(tell == "moderators" && !can)

    /** Told of it beyond REC: where it runs, someone else started it, and the site tells people more than REC. */
    val told: Boolean get() = state == "on" && !mine && id.isNotEmpty() && tell != "moderators" && tell != "indicator"

    /** The question to stay or leave, open until it is answered. */
    val asking: Boolean get() = told && tell != "notice" && answer != "agreed"

    /** The notice, until Got It or Do Not Show Again. */
    val noticeShown: Boolean get() = told && tell == "notice" && answer != "seen" && notice

    /** Who may watch it, for the notice and the question: nothing where it goes to the site's own list alone. */
    fun watchNote(say: (String, String) -> String): String {
        val who = when (watch) {
            "moderators" -> say("rec_watch_moderators", "Moderators can watch it afterwards.")
            "everyone" -> say("rec_watch_everyone", "Everyone in the conversation can watch it afterwards.")
            "people" -> say("rec_watch_people", "The people who were in the call can watch it afterwards.")
            else -> ""
        }
        val made = if (transcript == true) say("rec_transcript_too", "A transcript is made too.") else ""

        return listOf(who, made).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** The notice's title: who started it, to someone there as it started; that it records, to someone who joined since. */
    fun noticeTitle(late: Boolean, say: (String, String) -> String): String =
        if (by.isNotEmpty() && !late) say("rec_started_title", "%s started recording this call").replace("%s", by)
        else say("rec_title", "This call is being recorded")

    fun noticeNote(late: Boolean, say: (String, String) -> String): String =
        listOf(if (by.isNotEmpty() && late) say("rec_started", "%s started recording.").replace("%s", by) else "", watchNote(say))
            .filter { it.isNotEmpty() }.joinToString(" ")

    /** The question's lead: what staying means, with the microphone and camera held where the site holds them. */
    fun questionLead(say: (String, String) -> String): String = when {
        tell == "muted" && by.isEmpty() -> say("rec_ask_muted", "Your microphone and camera stay off until you agree to be recorded.")
        tell == "muted" -> say("rec_ask_muted_by", "%s started recording. Your microphone and camera stay off until you agree to be recorded.").replace("%s", by)
        by.isEmpty() -> say("rec_ask", "If you stay in the call, you agree to be recorded.")
        else -> say("rec_ask_by", "%s started recording. If you stay in the call, you agree to be recorded.").replace("%s", by)
    }

    fun questionYes(say: (String, String) -> String): String =
        if (tell == "muted") say("rec_agree", "I Agree") else say("rec_stay", "Stay in Call")

    /** What Start Recording says in Host Controls, by how everyone else is told. */
    fun startNote(say: (String, String) -> String): String = when (tell) {
        "moderators" -> say("rec_start_note_moderators", "Asks you first. Nobody else in the call is told.")
        "ask" -> say("rec_start_note_ask", "Asks you first. Everyone in the call is then asked to stay or leave.")
        "muted" -> say("rec_start_note_muted", "Asks you first. Everyone in the call then keeps the microphone and camera off until they agree.")
        "indicator" -> say("rec_start_note_indicator", "Asks you first. Everyone in the call then sees REC beside the call controls.")
        else -> say("rec_start_note_notice", "Asks you first. Everyone in the call then sees a notice.")
    }

    /** The start's confirm: how everyone else is told. */
    fun toldLead(say: (String, String) -> String): String = when (tell) {
        "moderators" -> say("rec_told_moderators", "Nobody else in the call is told: only moderators see REC, and nothing about it is posted in the conversation.")
        "ask" -> say("rec_told_ask", "Everyone in the call is asked to stay or leave, and so is anyone who joins later.")
        "muted" -> say("rec_told_muted", "Everyone else in the call keeps the microphone and camera off until they agree to be recorded, and so does anyone who joins later.")
        "indicator" -> say("rec_told_indicator", "Everyone in the call sees REC beside the call controls while it records, with no notice.")
        else -> say("rec_told_notice", "Everyone in the call sees a notice that it is recorded, and so does anyone who joins later.")
    }

    /** The stop's confirm: when it can be watched, and by whom. */
    fun readyLead(say: (String, String) -> String): String = when (watch) {
        "moderators" -> say("rec_ready_moderators", "Moderators can watch it in a few minutes.")
        "everyone" -> say("rec_ready_everyone", "Everyone in the conversation can watch it in a few minutes.")
        "admins" -> say("rec_ready_admins", "Site administrators can watch it in a few minutes.")
        "people" -> say("rec_ready_people", "The people who were in the call can watch it in a few minutes.")
        else -> say("rec_ready", "It is ready to watch a few minutes after it stops.")
    }

    /** The start's confirm: the admin's settings, each as a row. A row the host gave no words for is left out. */
    fun facts(audio: Boolean, say: (String, String) -> String): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()

        // An audio call is a video of its call screen too, in a layout of its own: the site sends none before it had one.
        if (size > 0 && (!audio || audioLayout.isNotEmpty())) {
            val shape = if (audio) {
                if (audioLayout == "gallery") say("rec_layout_gallery", "Gallery layout") else say("rec_layout_call", "As in the call")
            } else when (layout) {
                "gallery" -> say("rec_layout_gallery", "Gallery layout")
                "speaker_only" -> say("rec_layout_speaker_only", "Speaker only")
                else -> say("rec_layout_speaker", "Speaker layout")
            }

            rows.add(say("rec_fact_picture", "Picture") to "${size}p, $shape")
        }

        transcript?.let { on ->
            val value = when {
                !on -> say("rec_transcript_off", "Off")
                language.isEmpty() || language == "auto" -> say("rec_transcript_auto", "Automatic")
                else -> languageWords
            }

            if (value.isNotEmpty()) rows.add(say("rec_fact_transcript", "Transcript") to value)
        }

        if (watch.isNotEmpty()) {
            val value = when (watch) {
                "moderators" -> say("rec_watch_is_moderators", "Moderators")
                "everyone" -> say("rec_watch_is_everyone", "Everyone in the conversation")
                "admins" -> say("rec_watch_is_admins", "Site administrators")
                else -> say("rec_watch_is_people", "The people who were in the call")
            }

            rows.add(say("rec_fact_watch", "Who can watch") to value)
        }

        keepDays?.let { days ->
            val value = if (days > 0) keptWords else say("rec_kept_forever", "Until deleted")

            if (value.isNotEmpty()) rows.add(say("rec_fact_kept", "Kept") to value)
        }

        return rows
    }
}

/**
 * Whether the user joined while a recording ran rather than being in the call as it started, as the web's
 * useRecordingLayer tells it: connected before the recording was first seen running.
 */
class CallsRecordingLate {
    private var without = false
    private val late = HashMap<String, Boolean>()

    fun see(connected: Boolean, recording: CallsRecording?) {
        val state = recording?.state

        if (!connected) without = false else if (state != "on") without = true

        val id = recording?.id ?: ""

        if (state == "on" && id.isNotEmpty() && !late.containsKey(id)) late[id] = !without
    }

    fun isLate(id: String): Boolean = id.isNotEmpty() && late[id] == true
}

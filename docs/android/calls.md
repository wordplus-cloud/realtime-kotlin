# Calls

Calls between two people and group calls, in `cloud.wordplus:realtime-calls`, on the same relay and media servers as browsers: a phone and a browser call each other, and either may add someone. The Better Messages call screen, in `cloud.wordplus:realtime-ui`, draws them with no screen code of yours. Both libraries are optional.

- **Calls between two people** ring on every page and app the callee has open. They take one of three paths, tried in turn: directly between the two devices, through our relays (TURN) on the ports of the web, 443 and 80, when a network blocks that, and in a room on our media servers when even the relays cannot reach. A call moves on its own, without hanging up, and takes no credits on any path.
- **Group calls** run on the WordPlus Cloud media servers, with no limit on people in a call. Up to 32 people on video at once take no credits with 1,000 connections, and 12 more for each 1,000 after them, a person on audio counting half. Each one above takes a credit a minute from your account.
- **Encrypted end to end,** with keys only your server gives out: a call between two people on every path, and the group call it becomes. A group call your server names is encrypted end to end when its rule says `encrypt` ([what WordPlus Cloud sees](https://docs.wordplus.cloud/apps/calls/#what-wordplus-cloud-sees)).
- **Calls ring a locked phone and a closed app,** with a call's notification and Telecom, as phone calls do, once the app starts `SystemCalls` ([below](#ringing-a-locked-phone)).
- **User ids** are yours: digits or text such as `user_2abc`, up to 64 characters with no space. The SDK takes them as strings (`"34"`, `"user_2abc"`).

How a call starts, runs and ends, for the app and for your server: [how calls work](https://docs.wordplus.cloud/use-cases/how-it-works/#calls-between-two-people). What people build with them, from a support call to a meeting: [use cases](https://docs.wordplus.cloud/use-cases/overview/).

## Who may call

Your server declares calls by pattern, as it declares rooms ([calls in depth](https://docs.wordplus.cloud/apps/calls/#who-may-call)). Here a support call between two people is named `support:` and their two ids, smaller first: `support:34:42`. A meeting is named `meeting:` and its team: `meeting:daily`. The app asks your calls route (`{endpoint}/calls`) with its headers each time it places, answers or joins a call, as the browser does, and your rule answers. These rules decide:

1. A support call is only between the two people its name holds: whoever calls and whoever is called are both in it.
2. Your server asks twice, as the caller places the call (`side.role` is `caller`) and as the callee answers (`callee`). Here anyone called may answer.
3. Only placing the call is checked further: one of the two is staff, so a customer calls an agent and an agent calls a customer, but two customers never call each other.
4. A meeting is for the members of its team alone.
5. Its lead is its host (`admin`), who mutes and removes people from the web call screen ([the host](#the-host)).
6. A meeting is encrypted end to end (`encrypt`).

**Node**

```ts
realtime.calls( 'support:{a}:{b}', async ( user, withUser, { a, b }, call, side ) => {
    // 1. Both people are in the name, as text: user 34 is "34" there.
    const pair = [ a, b ];
    if ( ! pair.includes( String( user ) ) ) return false;
    if ( ! pair.includes( String( withUser ) ) ) return false;

    // 2. Anyone called may answer.
    if ( side.role === 'callee' ) return true;

    // 3. Placing it: one of the two is staff.
    const callerIsStaff = await isStaff( user );
    const calleeIsStaff = await isStaff( withUser );
    return callerIsStaff || calleeIsStaff;
} );

realtime.groupCalls( 'meeting:{id}', async ( user, { id } ) => {
    // 4. Members of the team only.
    if ( ! await isMemberOf( id, user ) ) return false;

    // 5. Its lead is its host.
    return { admin: await isLeadOf( id, user ) };
}, { encrypt: true } );   // 6. Encrypted end to end.
```

**PHP**

```php
$realtime->calls( 'support:{a}:{b}', function ( $user, $with, array $p, string $call, array $side ) {
    // 1. Both people are in the name, as text: user 34 is "34" there.
    $pair = [ $p['a'], $p['b'] ];
    if ( ! in_array( (string) $user, $pair, true ) ) {
        return false;
    }
    if ( ! in_array( (string) $with, $pair, true ) ) {
        return false;
    }

    // 2. Anyone called may answer.
    if ( 'callee' === $side['role'] ) {
        return true;
    }

    // 3. Placing it: one of the two is staff.
    $caller_is_staff = is_staff( $user );
    $callee_is_staff = is_staff( $with );
    return $caller_is_staff || $callee_is_staff;
} );

$realtime->groupCalls( 'meeting:{id}', function ( $user, array $p ) {
    // 4. Members of the team only.
    if ( ! is_member_of( $p['id'], $user ) ) {
        return false;
    }

    // 5. Its lead is its host.
    return [ 'admin' => is_lead_of( $p['id'], $user ) ];
}, [ 'encrypt' => true ] ); // 6. Encrypted end to end.
```

**Python**

```python
def may_call_support(user, with_user, p, call, side):
    # 1. Both people are in the name, as text: user 34 is "34" there.
    pair = [p["a"], p["b"]]
    if str(user) not in pair:
        return False
    if str(with_user) not in pair:
        return False

    # 2. Anyone called may answer.
    if side["role"] == "callee":
        return True

    # 3. Placing it: one of the two is staff.
    caller_is_staff = is_staff(user)
    callee_is_staff = is_staff(with_user)
    return caller_is_staff or callee_is_staff


def may_join_meeting(user, p):
    # 4. Members of the team only.
    if not is_member_of(p["id"], user):
        return False

    # 5. Its lead is its host.
    return {"admin": is_lead_of(p["id"], user)}


realtime.calls("support:{a}:{b}", may_call_support)
realtime.group_calls("meeting:{id}", may_join_meeting, encrypt=True)   # 6. Encrypted end to end.
```

- **`isStaff`, `isMemberOf` and `isLeadOf`** are your own lookups, in your own data.
- **What a rule may answer:** `false`, `true`, or an object with `info` (what the others see of the user, your `profile` name and avatar by default), and in a group call `publish` and `subscribe` (true unless said), `admin` and `hidden` (false unless said) ([who may call](https://docs.wordplus.cloud/apps/calls/#who-may-call)).

### Or a token from a route of your own

Whether someone may call or join can be your own logic, with no rule. A route of your server checks whatever it needs and makes a token, and the app starts with it. The app never reads the token.

On your server, in that route, with your check before it. `user` is who asks, from your session, and `to` the person they call:

```ts
const callToken = await realtime.callToken( user, to, { type: 'video' } );
const meetingToken = await realtime.groupCallToken( user, 'meeting:daily', { admin: isLead } );
```

The PHP package has the same names, and the Python package `call_token` and `group_call_token`. In the app, in a coroutine, with the token your route answered:

```kotlin
import cloud.wordplus.realtime.calls.joinGroupCall
import cloud.wordplus.realtime.calls.startCall

val call = rt.startCall(context, callToken)             // rings the person the token names
val meeting = rt.joinGroupCall(context, meetingToken)   // nothing is sent until `microphone` or `camera` say so
```

- **The person called needs no token.** The ring proves your server said yes, and `incoming.accept()` answers it as any other.
- **A token rings once,** so each call takes a new one. A call that outlasts its token gets another through your calls route, by what the first one proved.
- **A host or a recorder** comes from the token too: `admin` lets them act on others, and `record`, or `admin` unless it says no, lets them record. The acts go through your calls route, which takes that proof with no rule.
- **Someone taken out of a group call** needs a new token to come back: the old one is refused as `expired`.
- **Refusals** are a `CallException`: `bad_token` for what is no token, and `forbidden` for a token made for another user.

## The call screen

```kotlin
import cloud.wordplus.realtime.calls.calls
import cloud.wordplus.realtime.ui.CallScreen

val calls = rt.calls(context)
CallScreen.attach(context, calls)   // rings for every call coming in, and shows it once answered
CallScreen.onAnswered = { answered ->
    when (answered) {
        is IncomingCall.Answered.ToCall -> follow(answered.call)       // the call a ring became
        is IncomingCall.Answered.ToGroup -> follow(answered.group)     // or the group call it rang into
    }
}

viewModelScope.launch {
    val call = rt.call(context, "support:{pair}", "34", CallType.VIDEO)
    CallScreen.show(context, call, name = "Ann")

    val meeting = rt.groupCall(context, "meeting:daily", CallType.VIDEO)
    CallScreen.show(context, meeting, title = "Daily meeting")
}
```

- **It is the Better Messages screen itself,** the same files as its app: the ring with Answer and Decline, the call between two with the picture of the other side and your own in the corner, the grid of a group call, and the controls (microphone, camera, turning the camera round, the speaker and audio route, picture in picture, the people of a group call, hanging up).
- **It is an activity of its own** over the app, which the library declares. `CallScreen.hide()` puts it away and the call goes on: show a bar of your own meanwhile (`CallScreen.active` says a call is on), and `CallScreen.restore()` brings it back. It has no button for that of its own, so a person stays on it until the call ends unless you give them one: a [button in the dock](customizing.md) that calls `hide()`, as the Example Shop has.
- **The microphone and camera:** it asks for them when a call is answered, if the app has not been allowed them yet.
- **A call ringing during another** is answered busy.
- **A call that becomes a group call** comes back on the screen, as the group call.
- **Its words and colours:** `CallScreen.configure(CallScreen.Appearance(strings = mapOf(…), tokens = mapOf(…), tokensDark = mapOf(…), darkMode = false))`, the strings by the Better Messages keys (`answer`, `reject`, `end_call`, `calling`, `optimizing_video` over a camera changing codec …), the colours by its design tokens (`"--wplus-color-accent" to "#21759b"`). The English is the default for any string left out ([customizing](customizing.md)).
- `CallScreen.log` hands you the lines the screen writes, for your own log.

![The call screen on an Android phone, from its preview with no call: on the left a video call from Ann ringing in, with Answer and Decline. On the right a call to Ann ringing out, with the camera, microphone, speaker and hang up](https://docs.wordplus.cloud/img/android/call-ringing-calling.webp)

## Ringing a locked phone

`SystemCalls` makes the app's calls phone calls to Android, as the Better Messages app's are. A call rings a locked phone and a closed app with a call's notification, Answer and Decline, and a ring screen over the lock screen. Telecom carries its audio routes, a cellular call holds it, and a car or a watch answers it. It goes on with the app in the background, with a call's foreground service and Hang up in the notification.

Start it in your `Application`, because a call can start the process with nothing else running:

```kotlin
class ShopApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemCalls.start(this, SystemCalls.Options(name = "Shop", screen = MainActivity::class.java)) {
            Session.realtime()      // the signed-in user's connection, or null when nobody is signed in
        }
    }
}
```

And hand the call's messages over from your Firebase messaging service:

```kotlin
class Messaging : FirebaseMessagingService() {
    override fun onNewToken(token: String) = SystemCalls.pushToken(token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (SystemCalls.push(this, message.data)) return      // a call, or a call called off
        // your app's own messages
    }
}
```

- **How a closed app rings:** the phone goes with the user's line, so their calls ring it. A call sends it a high-priority message, the notification rings at once, and your lambda gives a connection. The SDK holds the line on it, and the call goes on as if it had come in while the app was open.
- **Answer on the notification** opens `screen`, your activity, with the call: it arrives in `SystemCalls.onAnswered { answered -> … }`, and the call screen (`realtime-ui`) shows it by itself.
- **With the app open,** a call rings on the app's own screen, as before, and the notification stays quiet under it. Answered on either, it is answered once.
- **The key:** WordPlus Cloud sends the message with your Firebase service account, which you add to the app on the account's Apps page, under Phone calls ([shipping](shipping.md#push-and-telecom)). WordPlus Cloud keeps it, and the realtime servers get only short-lived tokens made from it.
- **The caller's name** on the ring is the `name` in the caller's info from your server. The message carries that info sealed, and the phone opens it with the day's key, which the SDK keeps on the phone for this, encrypted with a key of Android's keystore ([where the keys are kept](encryption.md#where-the-keys-are-kept)). Google sees the call's id and name, the caller's user id and the call type, never the name of the caller.
- **Over a locked screen,** from Android 14, is an access the person grants: `SystemCalls.canRingFullScreen(context)` says whether the app has it, and `SystemCalls.openFullScreenSettings(context)` opens the page where they allow it. Without it a locked phone rings with nothing on its screen to answer from.
- **Signing out:** `rt.calls(context).stopRinging()` takes the phone off the user's line, so a closed app no longer rings for them.
- **A ring during a call** is answered busy.
- **Not without Telecom:** on Android 7 (API 24 and 25), or a device that refuses the app's registration, calls still ring with the notification, with no system audio routes. `SystemCalls.isStarted` says whether `start` was called.
- **Its words** are string resources: `wordplus_calls_answer`, `wordplus_calls_decline`, `wordplus_calls_incoming_video`, `wordplus_calls_incoming_audio`, `wordplus_calls_channel`, `wordplus_calls_ongoing_channel`. Give them in your app's resources to translate or change them.

How the system shows the calls:

```kotlin
SystemCalls.start(this, SystemCalls.Options(
    name = "Shop",                        // shown for a caller whose name is not known yet
    screen = MainActivity::class.java,    // the activity a call answered from the notification opens
    icon = R.drawable.ic_call,            // the notification's small icon, the system's call icon by default
)) { Session.realtime() }
```

## Calls of your own

The same calls, for screens you draw:

```kotlin
calls.onIncoming { incoming ->
    // incoming.from, incoming.info (name, avatar), incoming.type, incoming.group (a ring into a group call)
    incoming.onOver { reason -> hideRing() }                       // answered or declined, here or elsewhere, the caller gave up …
    viewModelScope.launch {
        when (val answered = incoming.accept()) {                  // or incoming.decline(busy = true)
            is IncomingCall.Answered.ToCall -> show(answered.call)
            is IncomingCall.Answered.ToGroup -> show(answered.group)
        }
    }
}

val call = calls.start("support:{pair}", "34", CallType.VIDEO)
call.onState { state -> label = state.wire }                      // ringing, connecting, connected, reconnecting, ended
call.onRemote {                                                    // what the other side sends changed
    remoteView.show(call.remoteCamera)
    screenView.show(call.remoteScreen)                        // the screen they share, null while they share none
    cameraOffBadge.isVisible = call.remoteMedia["camera"] != true
}
call.onEnded { reason -> close(reason) }                           // hangup, declined, busy, cancelled, group …

val remoteView = CallVideoView(context)                            // a View
val ownView = CallVideoView(context).apply { mirrored = call.frontCamera }
ownView.show(call.localCamera)

call.microphone = false                                            // mute
call.camera = false                                                // stop sending the camera
call.flipCamera()                                                  // the front one to the back, and back
call.end()                                                         // hangs up, or calls it off while it rings
remoteView.release()                                               // when the view goes

calls.onError { code -> log(code) }                                // the line refused, or a group call that could not join
calls.onCall { call -> keepToMyRules(call) }                       // every call started or answered, the call screen's too
calls.onGroup { group -> keepToMyRules(group) }                    // every group call joined, the call screen's too
```

- **Ask for `RECORD_AUDIO` and, for video, `CAMERA`** before a call, as for any screen of your own. The library manifest declares them. One allowed only after the call began: call `call.permissionsGranted()`, and the call opens it then.
- **`call.remoteMedia`** says what the other side has on: `camera`, `microphone`, `screen_share` and `screen_share_audio`, each true or false, read again at each `onRemote`.
- **`call.path`** says what carries it: `DIRECT`, `RELAY` (TURN) or `ROOM`, and `call.onPath` hears it change. `call.onWaiting` hears the room a call moved to wait for a place on the mesh (a `CallWait`: `until` and `reason`), and it goes on by itself. `call.peer` and `call.peerInfo` who the other side is, `call.role` whether this app is its `CALLER` or `CALLEE`, `call.frontCamera` whether the camera sent is the front one.
- **`calls.incoming`** lists the calls ringing for the user now.
- **`calls.onError`** hears what went wrong away from any one call: your server refused the line its calls ring on (`forbidden`, `expired` …), or a group call could not join its room (`room_failed`, and the group call ends with it).
- **Where the sound goes:** a video call starts on the speaker and an audio call at the earpiece. The call screen's speaker button moves it, and its settings list every output, a headset or a car included. With `SystemCalls`, Telecom carries the routes, so a car or a watch takes the call. A screen of your own moves it with Android's `AudioManager`, as for any call.
- **`calls.onCall` and `calls.onGroup`** hear each call this app started or answered and each group call it joined, whoever asked for it, a ring answered on the call screen or a call of its that became a group call among them, so an app can keep to rules of its own, such as how long a call may last. The room a call moved to when no path carried it is still that call, and neither hears it.
- **`calls.people(name)`** gives who this user may add to calls named `name`, as your server lists them (a `CallPerson`: `id`, `name` and `avatar`), none when it lists nobody or could not answer. Your server's `people` answers it ([adding people](https://docs.wordplus.cloud/apps/calls/#adding-people)).
- **`calls.may(asks)`** tells whether this user may call each person about each call, `listOf(CallAsk("support:34:42", "42"))`, in the order asked: what a list of people asks before it offers call buttons. Null where your server gave no answer, a route of its own that does not know the question or a failed request, and placing the call still asks the rule. Any number of questions, sent 100 a request.
- **`calls.stopRinging()`** lets go of this app line: its user calls no longer ring here.
- **Taking a handler off:** each `on…` of `calls`, a call, a group call and a ring gives a `CallListener`, which `off(listener)` on the same object takes off.
- `start`, `group`, `accept` and `add` switch to the main thread themselves. Call them from any coroutine.

### A meter and the camera that opens

For a device check of your own: `call.microphoneLevel()` and `group.microphoneLevel()` say how loud the microphone goes out, from 0 to 1, null while it is off. `call.preferCamera(front = false)` and `group.preferCamera(front = false)` choose the side the next camera opens on, and `frontCamera` says it. A camera on turns round with `flipCamera()`.

## Calls on your own signalling

A call between two people can travel by signalling other than this SDK's, and still run on its call engine and show on its call screen: Better Messages' own, or a transport of your app.

```kotlin
// Better Messages: a connection on its namespace, with its handshake, and its `p2pCall`.
val bm = Realtime(RealtimeOptions(app = site, endpoint = siteRest, server = "https://cloud.better-messages.com",
                                  namespace = "/", auth = buildJsonObject { put("sid", site); put("uid", 7); put("sk", userKey); put("v", "3.2.0") }))
bm.connect()
val call = bm.calls(context).call(over = P2PCallTransport(bm), id = "4711", role = Call.Role.CALLEE, peer = "34",
                                  peerInfo = buildJsonObject { put("name", "Ann") }, type = CallType.VIDEO, signalKey = callKey,
                                  roomServer = siteMediaServer)
CallScreen.show(context, call, name = "Ann")
```

- **`calls.call(over, id, role, peer, peerInfo, type, signalKey, mediaKey, roomServer)`** joins a call that whoever made it on the relay already made, a site's server for Better Messages: `CALLER` rings until the other side joins, `CALLEE` connects. `signalKey` seals the signals and never reaches the relay, `mediaKey` encrypts the room the call moves to when neither path works, and `roomServer` says where that room is when the relay hands it over by its token alone, as Better Messages' does (its site has it, from the relay's answer to `/p2p/call/create`). It throws the relay's refusal as a `CallException`: `no_call`, `not_participant`, `call_ended`, `answered_elsewhere`, or `signaling_unavailable` when the transport answered nothing. It switches to the main thread itself.
- **`P2PCallTransport(rt)`** is Better Messages' `p2pCall`, on a connection opened on its namespace. The connection moves between servers and comes back after a drop as on `/channels`, and the call joins again on each new socket.
- **`CallTransport`** is the interface to carry it your own way, to a relay that answers the same ops: `connected`, `send(payload, ack)` with the op and its answer, `listen(callId, session, handler)` for one session's events, and `onReconnect(listener)` when the transport is back. The engine calls it on the main thread, and takes what it answers to the main thread itself.
- **What stays the SDK's own:** such a call adds nobody (`add` throws `not_supported`), renews no token of this SDK, and ends as `group` where the relay moves it into a group call, which its product joins its own way. Rooms, presence and calls by name are `/channels`' protocol.

## Adding people

Once a call is up, either side may ring someone else into it. Both sides go on in a group call of their own, without hanging up:

```kotlin
call.onGroup { group -> follow(group) }        // both sides, and the call then ends as `group`
val invite = call.add("43")                     // rings user 43, throws its code
invite.onState { state -> label.text = state }  // answered, rejected, busy, no_answer, cancelled
invite.cancel()                                 // calls it off while it rings
```

- **With a route of your own,** the app sends it `call.ref`, your route checks and answers `realtime.callToken( user, to, { into: ref } )`, and `call.add(token)` rings them. A string that opens as a call's token is a token, any other is a user id. A token made for another call is refused as `forbidden`. `group.ref` is the same for a group call made from a call, and null for any other.
- **Your rule decides,** as for any call: the user calling the person about the call name, then the person taking a call from them.
- **`invite`** is the `CallInvite` rung: `invite.to`, and `invite.state`, `ringing` and then how it ended, in the words of the relay: `answered`, `rejected`, `busy`, `no_answer` or `cancelled`. `invite.onState` hears it change. A ring runs out after 45 seconds. The person comes into `group.people` once they answer.
- **`invite.cancel()`** calls it off while it rings, from the app that rang: `forbidden` for anyone else, and nothing for a ring that has ended.
- **`group.invites`** lists the rings into the group call this app heard of, its own and those of anyone else in it, and `group.onInvite` hears each new one (`invite.from` who rang, `invite.group` the call).
- **The person rung in** gets an `IncomingCall` whose `group` is set, and `accept()` answers `ToGroup`.
- **`group.add(user)`** rings more people in, from anyone in it. A group call your server names (`calls.group`) takes nobody that way: `not_a_call`.
- **`invite.canCancel`** says whether `cancel()` can call it off now: it still rings, and this app rang it.

### On the call screen

```kotlin
CallScreen.addPerson = true                             // Add Person in the dock, off unless you turn it on
```

- **Add Person** shows once a call is up, in a call between two and in a group call made from one, as the `add` control of the [dock](customizing.md#2-a-piece-of-your-own). It offers whom your server lists (`calls.people`, your server's `people`), less anyone in the call and anyone `calls.may` answers no for.
- **A press rings them** and the card stays, each row saying how its ring stands: Ringing… with Cancel for a ring of this app, then Answered, Declined, Busy, No answer or Cancelled. A person whose ring ended can be rung again. A call between two goes on as a group call under the card, on the same screen.
- **A ring that did not go out** closes the card and the screen says why.
- **Its words:** `add_person`, `add_person_loading`, `add_person_empty`, `add_person_ringing`, `add_person_answered`, `add_person_rejected`, `add_person_busy`, `add_person_no_answer`, `add_person_cancelled`, `cancel`, `call_forbidden` and `call_unavailable`.
- **Unlike the web,** the card stays open after a press, where the web closes it, and it says how each ring stands. The web offers it in a group call your server names too, where the ring is refused. Rings of people your server does not list for this user are not drawn.

## Group calls

How a group call starts, who gets in, how your server runs it and how it ends: [how group calls work](https://docs.wordplus.cloud/use-cases/how-it-works/#group-calls).

```kotlin
val group = calls.group("meeting:daily", CallType.VIDEO)
group.setMicrophone(true)                       // nothing is sent into a group call until asked
group.setCamera(true)
group.flipCamera()                              // the front camera to the back, and back
group.onPeople { people = group.people }        // List<GroupPerson>: user, info, camera, microphone, screen, speaking, publish, optimizing, quality
group.onState { state -> label = state.wire }
group.onEnded { reason -> close(reason) }       // left, removed, closed, disconnected, room_failed
group.leave()
```

- **`group.me`** is this app's own user in the room, as the others see them: a `GroupPerson` with their info as your server gave it, null until connected. A tile for oneself draws from it.
- **`group.quality`** says how this app's connection stands: `EXCELLENT`, `GOOD`, `POOR`, `LOST` or `UNKNOWN`, and `group.onQuality` hears it change. `person.quality` says the same of each person, for a mark on their tile.
- **`group.uploadJammed`** says whether the upload this app sends on is jammed: what it sends waits seconds on the person's own line, or gets no answer at all, while what is sent to it still arrives. Others may then see its video freeze or hear its sound break up, and the media server rates such a line good, so show it as poor whatever `quality` says. `group.onUploadJam` hears it change.
- **The sound comes first** on an upload too narrow for the call: the microphone comes down to 32 kbps, then the camera gets smaller a step at a time until the sound gets through, and comes back as it holds. The microphone also lets its media server ask it again for what the upload lost. Nothing to set.
- **`group.onError`** hears what goes wrong in a call that goes on: the camera or microphone could not go out (`publish_failed`, `source` says which), or a track could not be encrypted or decrypted (`encryption_failed`).
- **Joining again** after being removed, or after the call ended for everyone, works at once: `calls.group` waits for the next second by itself, since the realtime server refuses a token signed in the second that happened.
- **A place on the mesh:** a join may wait for one when the servers are busy or full. `group.waiting` is a `CallWait` then, `until` when and `reason` why (`busy`, `slot`, `full` or `unreachable`), and `group.onWaiting` hears it. `group.state` stays `CONNECTING`, and the join goes on by itself.
- **`group.encrypted`** says whether its media is encrypted end to end: always for the group call that comes from a call, and for one your server names when its rule says `encrypt`.
- **Drawing it yourself:** a `GroupVideoView` shows anyone's camera, `view.show(person.identity, group)`, or the screen they share with `GroupVideoSource.SCREEN`, and `view.showOwn(group)` this app's own camera. Audio plays by itself.
- **A camera changing codec:** a browser in the call may move its camera to the codec its computer sends best, which takes the camera away for a moment. `GroupPerson.optimizing` is true from its word until the new camera arrives, the camera is turned off, or the media server's failsafe has passed (10 seconds unless it sets another): draw a placeholder such as "Optimizing video quality..." over their picture then, not a camera turned off. The call screen does it for you, and holds its placeholder until the new camera's first frame.
- **What this app sends:** its camera, its microphone and its screen ([sharing the screen](#sharing-the-screen)). A group call of the SDK raises no hand, on the web either: [a room of yours carries them](#speakers-and-listeners).

### The host

The host of a group call is whom its rule answers `admin: true`. In a browser, the call screen gives the host buttons on the tile of each of the others: mute, let speak, make a listener, remove ([permissions](https://docs.wordplus.cloud/apps/permissions/#group-calls)).

The call screen on Android gives the host the same: a menu on the tile of each of the others, with Spotlight for Everyone, and Host Controls in the dock. In a screen of your own, each act has a word of its own:

```kotlin
group.can.admin              // true for the host
group.mute("42")             // their microphone off, which they may turn on again
group.makeListener("42")     // a listener, who sends nothing
group.letSpeak("42")         // a speaker again
group.remove("42")           // out of the call
group.spotlightOn("42")      // on the main tiles for everyone, up to four
group.spotlightOff("42")     // off them, or everyone with no user
group.spotlit                // who is on them, by their user ids
group.onSpotlit { users -> redraw(users) }
```

Each goes through your calls route, which asks the call's rule again and takes it from the host alone. Each answers whether it changed anything, and throws its code: `forbidden` for an app that isn't the host, `not_moderated` for a room that isn't a group call your server names (a call that became one), `spotlight_full` for a fifth person in the spotlight.

Ending the call for everyone is your server's, from a route of yours that checks who asks, [below](#from-your-server) ([buttons for the host](https://docs.wordplus.cloud/use-cases/how-it-works/#buttons-for-the-host)). The app hears each change: a person muted in `onPeople`, its own right to speak in `onPermissions`, the app taken out in `onEnded` with `removed`, and the call ended for everyone with `closed`.

### Recording

A group call is recorded through the same route, by whoever its rule answers `record: true`, else its host:

```kotlin
val recording = group.startRecording()              // its id and state: recording, stopping, finishing, complete or failed
group.stopRecording(recording.id)
```

Each throws its code: `forbidden` for anyone else, `not_moderated` as above, or WordPlus Cloud's refusal (`no_credits`, `not_included`, `already_recording` and the rest). Its files go to your server, through the server package's `recordings`. It needs WordPlus Cloud's recording routes, which are [still to come](https://docs.wordplus.cloud/recording/): until then a start is refused.

`group.canRecord` says whether to offer it, in a group call your server names whose media the recorder can read: for one joined by its name, to whoever the calls route's answer lets record (its `record`, which the server package gives whoever your rule answers `record: true`, else its host), as the browser reads it; for one joined with a token, to whoever the token lets record. `group.isRecording` is the room's flag, whoever started it, and `group.onRecording` says as it changes.

- **On the call screen:** whoever `canRecord` offers it to has Start Recording in Host Controls, which asks first. REC then stands in the bar with its time, and its Stop asks too. Everyone else sees REC and a notice, as it starts or as they join, with Got It and Do Not Show Again. A refusal is said in the words of the screen and changes nothing: a button of your own gets the code.
- **A host who did not start it** sees REC and has no Stop: the route stops a recording by its id, and the flag of the room names none. Your server can stop it, with `recordings.stop`.
- **Someone your rule answers `record: true` who is not its host** has Host Controls with Start Recording alone, and none of a host's acts on people.

### Speakers and listeners

A class, a town hall or an audio room: your rule makes the listeners (`publish: false`), and your server lets one speak while the call runs, with `realtime.call( "class:7" ).permit( user, { publish: true } )` ([speakers and listeners](https://docs.wordplus.cloud/apps/calls/#speakers-and-listeners)). The app hears it:

```kotlin
group.onPermissions { can ->
    group.microphone = can.publish   // let on: it speaks. Back to listening: the media server took it down already
}
val speakers = group.people.filter { it.publish }
```

- **`group.can`** is what the app may do now, `GroupPermissions(publish, subscribe)`: what your rule said, then what your server changed.
- **Back to listening,** `microphone` and `camera` turn false, and nothing is sent again on a reconnect.
- **Everyone sees who may speak:** `GroupPerson.publish`, with `onPeople` when it changes.
- **A listener asks to speak** through a room of yours that carries hands, as the browser does: `rt.join("class:7").emit("hand", buildJsonObject { put("up", true) })` from the app, when the room rule lists `hand` in `emit`.

## Sharing the screen

The call screen has a button for it, in a call between two and in a group call, and does all of this by itself. In a screen of your own, the system asks first, in a dialog only an activity can start, and its answer goes to the call:

```kotlin
private val askForScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { answer ->
    if (answer.resultCode == RESULT_OK) scope.launch { call.setScreenShare(true, answer.data) }
}

askForScreen.launch(ScreenShare.permissionIntent(this))           // the dialog of the system
call.onScreenShare { sharing -> shareButton.isActivated = sharing }  // it began or it stopped
scope.launch { call.setScreenShare(false) }
```

- **`Call` and `GroupCall`** have all of it: `setScreenShare(on, permission)`, `sharingScreen` and `onScreenShare`. A `LiveStream` has the first two ([streams](streams.md#going-live)).
- **`setScreenShare`** answers whether the screen is shared now. It is false before the call is up, without an answer, for one who may not send, and where the system refuses. In a group call `onError` says so too (`publish_failed`, `source` `screen`).
- **An answer is good for one share:** stopping and sharing again asks again.
- **The person may stop it from the system,** and `onScreenShare` hears that as well.
- **The foreground service** Android 14 requires while a screen is captured comes with the library, with its permission ([shipping](shipping.md#permissions)). The system shows its notification for as long as the share lasts.
- **On the other side,** a browser shows the screen as it shows one from another browser, and a phone with `call.remoteScreen` or a `GroupVideoView` of `GroupVideoSource.SCREEN`.

## From your server

Your server sees the group calls going on and runs them, with no app open: who is in a call, muting someone, letting them speak or making them a listener, taking them out, and ending the call for everyone. Call these from a route of yours that checks the user asking is the host:

**Node**

```ts
await realtime.listCalls();                     // [ { name, people, started_at } ]
const meeting = realtime.call( 'meeting:daily' );
await meeting.get();                            // { people, started_at, list: [ { user, joined_at, camera, microphone, screen } ] }
await meeting.mute( 43, 'microphone' );         // 'camera' or 'screen' too
await meeting.permit( 43, { publish: false } ); // a listener now
await meeting.remove( 43 );                     // out now
await meeting.end();                            // out, everyone
```

**PHP**

```php
$realtime->listCalls();
$meeting = $realtime->call( 'meeting:daily' );
$meeting->get();
$meeting->mute( 43, 'microphone' );
$meeting->permit( 43, [ 'publish' => false ] );
$meeting->remove( 43 );
$meeting->end();
```

**Python**

```python
realtime.list_calls()
meeting = realtime.call("meeting:daily")
meeting.get()
meeting.mute(43, "microphone")
meeting.permit(43, publish=False)
meeting.remove(43)
meeting.end()
```

A call between two people runs between their devices, so it is not in `listCalls()`. More in [calls from your server](https://docs.wordplus.cloud/apps/calls/#from-your-server).

## Why a call ended

`call.endReason`, and the reason `onEnded` gets: the same codes as the browser ([why a call ended](https://docs.wordplus.cloud/apps/calls/#why-a-call-ended)), plus `connection_failed` when no path connected. A group call ends with `left` when the app leaves it, `removed` when your server or a host took this user out, `closed` when the call was ended for everyone, `disconnected` when the media server lets the app go for anything else (its connection did not come back), and `room_failed` when it could not join. A call that could not start throws a `CallException` with the code: `forbidden`, `no_user`, `not_included`, `auth_failed` ([errors](https://docs.wordplus.cloud/apps/errors/#calls)).

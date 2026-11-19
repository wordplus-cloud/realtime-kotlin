---
name: wordplus-realtime-android
description: Adds realtime features to an Android app written in Kotlin with the WordPlus Cloud libraries (cloud.wordplus:realtime, realtime-calls, realtime-ui), on the same rooms as the website of the app - rooms with emit, emitWithAck and volatile, events as flows, presence and who is online, history so nothing is lost after the background, what the server of the app sends the user, video and audio calls between a phone and a browser, group calls, live streams, and the native call and stream screens of Better Messages, rooms sealed end to end and calls encrypted end to end. Use when Kotlin code imports cloud.wordplus.realtime, or when an Android task asks for live updates, chat, presence, websockets or calls on WordPlus Cloud.
---

# The WordPlus Cloud realtime SDK for Android

The app joins rooms its server declares, with the server routes and the token it signed in with. The server is that of the website (Node, Laravel, Django …, with `@wordplus/realtime`, `wordplus/realtime-php` or `wordplus-realtime`): it declares rooms and calls, says who may join, signs it, and sends.

## Pick the feature

| The task asks for | Use | Guide |
|---|---|---|
| Updates sent from the server | `rt.join( "order:1042" ).eventsOf<T>( "updated" )`, `once`, `off`. The server `to( room ).emit()` | [rooms](https://docs.wordplus.cloud/android/rooms/), [sending](https://docs.wordplus.cloud/android/sending/) |
| A notice to one user | `rt.on( "notice" )`. The server `toUser( id ).emit()` | [sending](https://docs.wordplus.cloud/android/sending/#to-a-user) |
| Who is in a room, or online anywhere | a presence room `members`. `rt.online.watch( ids )` | [presence](https://docs.wordplus.cloud/android/presence/) |
| Drawing who is online and who is typing | `OnlineList`, `PersonRow`, `RowAction`, `OnlineCount`, `Avatar`, `ActivityIndicator`, fed by the app from a room's `members`: none reads a room | [presence](https://docs.wordplus.cloud/android/presence/#the-views) |
| Chat, typing, reactions | the room `emit` list on the server. `emitWithAck`, `volatile.emit`, `volatile.emitWithAck` in the app | [events](https://docs.wordplus.cloud/android/events/) |
| Calls, with a ring | `rt.calls( context ).start`, `CallScreen.attach( context, calls )` | [calls](https://docs.wordplus.cloud/android/calls/) |
| Calls that ring a locked phone and a closed app, with Telecom | `SystemCalls.start( this, Options( name, screen ) ) { connection() }` in `Application.onCreate`, `SystemCalls.pushToken` and `SystemCalls.push` from the app's `FirebaseMessagingService`, the Firebase service account on the Apps page | [calls](https://docs.wordplus.cloud/android/calls/#ringing-a-locked-phone), [shipping](https://docs.wordplus.cloud/android/shipping/#push-and-telecom) |
| A meeting | `calls.group( "meeting:daily" )`, `CallScreen.show( context, group )` | [calls](https://docs.wordplus.cloud/android/calls/#group-calls) |
| A class, a town hall, an audio room: listeners let on to speak | `publish: false` in the rule, `permit( user, { publish: true } )` on the server, `group.onPermissions`, `GroupPerson.publish` | [calls](https://docs.wordplus.cloud/android/calls/#speakers-and-listeners) |
| A host muting, removing or ending for everyone | `admin: true` in the rule. The call screen gives the host its menus. In a screen of your own, `group.mute( "42" )`, `makeListener`, `letSpeak`, `remove`, `spotlightOn`, `spotlightOff`, through the calls route. Ending for everyone is the server's `call( name ).end()`, from a route of the app that checks the host. The app hears it in `onPeople`, `onPermissions`, `onSpotlit`, `onEnded( "removed" )`, `onEnded( "closed" )` | [calls](https://docs.wordplus.cloud/android/calls/#the-host) |
| Who may be added or called, for a list of people | `calls.people( name )`, `calls.may( listOf( CallAsk( name, id ) ) )` | [calls](https://docs.wordplus.cloud/android/calls/#calls-of-your-own) |
| Keeping to rules of the app in every call, the call screen's too | `calls.onCall`, `calls.onGroup`, and `off( listener )` for any handler | [calls](https://docs.wordplus.cloud/android/calls/#calls-of-your-own) |
| Following a ring into a group call, or calling it off | `invite.onState`, `invite.canCancel`, `invite.cancel()`, `group.invites`, `group.onInvite` | [calls](https://docs.wordplus.cloud/android/calls/#adding-people) |
| Add Person on the call screen | `CallScreen.addPerson = true`, off by default | [calls](https://docs.wordplus.cloud/android/calls/#on-the-call-screen) |
| What goes wrong in a group call, a join that waits for a place, this user's own tile | `group.onError`, `group.waiting`, `group.onWaiting`, `group.me` | [calls](https://docs.wordplus.cloud/android/calls/#group-calls) |
| Sharing the screen on a call, a group call or a stream, and the screen the other side shares | `ScreenShare.permissionIntent`, `setScreenShare(on, permission)`, `sharingScreen`, `onScreenShare`, `call.remoteScreen` | [calls](https://docs.wordplus.cloud/android/calls/#sharing-the-screen) |
| A live stream, its webhooks, what the server does with it | the `streams()` rule, `rt.calls( context ).stream( name )`, the server's `stream( name )` and `stream.live`, `stream.ended` webhooks | [live streams](https://docs.wordplus.cloud/android/streams/) |
| The connection's state | `rt.state`, `rt.onState`, `rt.on( "error" )`, `rt.on( "moved" )` | [getting started](https://docs.wordplus.cloud/android/getting-started/#the-connection) |
| The call screen changed: its look, a piece of its own, or its whole code | `CallScreen.configure( Appearance( tokens = … ) )`, `CallScreen.pieces`, `./gradlew wordplusUiAdd` | [customizing](https://docs.wordplus.cloud/android/customizing/) |

Prefer the Better Messages call screen (`realtime-ui`) unless the task asks for screens of its own. A screen the app makes itself draws the video of a group call with `GroupVideoView` (`show( person.identity, call )`, `showOwn( call )`).

A live stream (one presenting, everyone watching, with a chat): the `streams( 'show:{id}', … )` rule of the server, then `rt.calls( context ).stream( name )` on the main thread (`video = false` for a screen with no picture, `watch()` later) → `LiveStream` (`state`, `media`, `viewers`, `messages`, `tiles`, then `onChange`, `onMessage`, `onReactions`, `onEnded`, then `send`, `react`, `leave`, `qualities`, `setQuality`, `stats`, `audience`, `waiting` with its reason, for a viewer `canAskStage`, `hand`, `joinStage`, `declineStage`, `leaveStage`, and for the host `preview`, `setCamera`, `setMicrophone`, `setScreenShare`, `goLive`, `end`), its picture in a `LiveStreamVideoView` (`show( stream )`, `release()`), or the whole of it drawn ready, `LiveStreamScreen.open( activity, stream, title )` of `cloud.wordplus:realtime-ui` (the picture, Live and the count, the chat and a heart, what a viewer does about the stage, full screen): [live streams](https://docs.wordplus.cloud/android/streams/).

## Customizing the call screen

Take the first step that does what the task asks ([customizing](https://docs.wordplus.cloud/android/customizing/)):

1. **The look:** `CallScreen.configure( CallScreen.Appearance( strings =, tokens =, tokensDark = ) )`: `--wplus-color-accent` and the other colours, and those of the screen itself, `--wplus-call-canvas` (the stage), `--wplus-call-tile`, `--wplus-call-btn` (each control) and `--wplus-call-dock`, as the web names them. Their names before, `--bm-…`, still work.
2. **A piece:** `CallScreen.pieces = CallScreen.Pieces( controls =, status =, ringing =, tile =, stage = )`. `controls` gets ours by id (`answer`, `settings`, `flip`, `camera`, `mute`, `speaker`, `pip`, `share`, `host`, `end`, each where the screen shows it, `add` with `CallScreen.addPerson` on: no raise hand) and returns the dock buttons. `CallScreen.CallControl.own( context, id, label, icon ) { … }` is one the app adds (`icon` a 24-grid path, `CallControl.icons` has ours). Hanging up stays last.
3. **The code:** the Gradle plugin `cloud.wordplus.realtime-ui`, then `./gradlew :app:wordplusUiAdd` copies the screen into `src/main/kotlin` and its tests into `src/androidTest/kotlin`. Then the module drops `cloud.wordplus:realtime-ui`, adds `cloud.wordplus:rtc-client-android` (the room client of the calls) and `androidx.core:core-ktx`, and declares the activities the task prints. Change anything in the copy. Keep `realtime-calls` under it.

- **After each change:** build, look at every state in `CallScreen.preview(context)` (calling, ringing in, connecting, voice, video, microphone and camera off, the other side lost, reconnecting, group calls, its Dark and RTL buttons), run `./gradlew connectedDebugAndroidTest` with a copy, then try a call both ways, a group call, the camera refused, the TalkBack labels, picture in picture, the lock screen.
  - **To see a state yourself:** export the preview in the debug manifest (`tools:replace="android:exported"`, as the customizing page shows), then `adb shell am start -n <applicationId>/cloud.wordplus.realtime.ui.CallScreenPreviewActivity --es cloud.wordplus.realtime.ui.preview.shown <CALLING|RINGING|CONNECTING|VOICE|VIDEO|OFF|PEER_LOST|RECONNECTING|GROUP|GROUP_VOICE>` and `adb exec-out screencap -p > shot.png`. It draws the pieces set by then, so set them in `Application.onCreate`.
  - **Make an own button once if you can.** The dock keeps one made anew under the same id, but the piece runs on every change of the call.
- **When the library updates:** `wordplusUiDiff`, `wordplusUiUpdate` (takes ours where the app never changed a file), merge the rest, `wordplusUiAccept`.

## Workflow

1. **The server** already answers the realtime routes for the website. Make it read the app user from its bearer token, and return `realtime.config( user )` with the token at sign-in ([getting started](https://docs.wordplus.cloud/android/getting-started/#3-your-server-knows-the-app-user)).
2. **Add the libraries** from Maven Central (`cloud.wordplus:realtime:1.0.0`, and `realtime-calls`, `realtime-ui` for calls), with `google()`, `mavenCentral()` and, for calls, `maven( "https://jitpack.io" )`.
3. **Connect once** per signed-in user, in a ViewModel or an object of the app: `Realtime( RealtimeOptions.fromConfig( config, server, headers = { … }, callbackExecutor = ContextCompat.getMainExecutor( context ), nodeStore = TextNodeStore( … ) ) )`, then `rt.connect()`. `rt.close()` at sign-out.
4. **Join rooms and listen.** Send from the app only what a room `emit` lists, and everything that matters through the server.
5. **Calls:** ask for `RECORD_AUDIO` and `CAMERA` at run time (the call screen asks on answering). Attach the call screen once after connecting. For calls that ring a closed app: hold the connection in the `Application` (not an activity's ViewModel, which a push-started process has none of), `SystemCalls.start` in `Application.onCreate` with a lambda that gives it, Firebase with its messaging service handing calls to `SystemCalls`, `POST_NOTIFICATIONS` asked for on Android 13 and later, and `rt.calls( context ).stopRinging()` at sign-out.
6. **Verify** (below).

## Template

```kotlin
val rt = Realtime(RealtimeOptions.fromConfig(config, server,
    headers = { mapOf("Authorization" to "Bearer $token") },
    callbackExecutor = ContextCompat.getMainExecutor(context)))
rt.connect()

@Serializable data class OrderUpdate(val status: String)
viewModelScope.launch { rt.join("order:1042").eventsOf<OrderUpdate>("updated").collect { (update, _) -> status = update.status } }

val chat = rt.join("chat:34:42")
viewModelScope.launch {
    val ack = chat.emitWithAck("message", buildJsonObject { put("text", "Hi") })
    chat.volatile.emit("typing", buildJsonObject { put("on", true) })
}

CallScreen.attach(context, rt.calls(context))
viewModelScope.launch { CallScreen.show(context, rt.calls(context).start("support:{pair}", "34", CallType.VIDEO), name = "Ann") }
```

## Rules

- **The app holds no secret:** the WordPlus Cloud secret and local key stay on the server. The app gets keys and identity from `config()`.
- **One `Realtime` per signed-in user,** shared by the app screens.
- **Give `callbackExecutor` the main thread executor** to update views from handlers. The default is a thread of the SDK.
- **User ids** are the app's own, digits or text, passed as strings (`"34"`, `"user_2abc"`).
- **Calls ring a closed app only with `SystemCalls` started in `Application.onCreate`** and the app's FCM messages handed to `SystemCalls.push`. Hand it every message first: it answers false for any that is not a call's.
- **HTTPS** for the server in release builds. Cleartext to `10.0.2.2` in a debug network security config only.
- **Do not write what the Android SDK lacks:** no raise hand on a group call, on the web either (a listener asks through a room of the app, `docs/android/calls.md`, Speakers and listeners), no ending a call for everyone from the app (that is the server's `end()`), and no Stop for a host who did not start the recording (the room's flag names no id: `group.isRecording` and `group.onRecording` say it runs, `group.canRecord` whether to offer Start Recording, which the call screen does). A live stream is not end to end encrypted.
- **Server rules written out,** as the guide writes them: a plain sentence of what each rule decides, then the rule with numbered comments, never a one-line chain of `&&`.

## Verify

- `rt.onState` reaches `ConnectionState.Connected`. No `error` event on `rt`.
- A room the user may join sends `joined`. One they may not sends `error` with `forbidden` (or `no_rule`).
- An emit from the server reaches the app handler opened, not as a sealed string.
- `emitWithAck` returns an `Ack`. An event the room does not list throws `RealtimeException( "not_allowed" )`.
- Calls: the app and a browser signed in as two users. Each rings the other. `call.state` reaches `CallState.CONNECTED`.

## The guide

[Getting started](https://docs.wordplus.cloud/android/getting-started/) · [rooms](https://docs.wordplus.cloud/android/rooms/) · [presence](https://docs.wordplus.cloud/android/presence/) · [sending](https://docs.wordplus.cloud/android/sending/) · [events](https://docs.wordplus.cloud/android/events/) · [calls](https://docs.wordplus.cloud/android/calls/) · [live streams](https://docs.wordplus.cloud/android/streams/) · [encryption](https://docs.wordplus.cloud/android/encryption/) · [customizing](https://docs.wordplus.cloud/android/customizing/) · [shipping](https://docs.wordplus.cloud/android/shipping/) · [the example app](https://docs.wordplus.cloud/android/example/). The same pages are in this repository `docs/android/`.

# cloud.wordplus:realtime for Kotlin

WordPlus Cloud's realtime SDK for Android and other Kotlin apps: rooms with events and acks, presence, live counts,
history, who is online and what the app's server sends the user, on the same realtime servers and protocol as the
browser's, sealed end to end with keys only the app's server hands out. A plain Kotlin/JVM library, which Android apps
take as it is.

```kotlin
val rt = Realtime(RealtimeOptions(app = "s1500000007", endpoint = "https://shop.example.com/api/realtime",
                                  headers = { mapOf("Authorization" to "Bearer ${session.token}") },
                                  callbackExecutor = ContextCompat.getMainExecutor(context)))
rt.connect()
val chat = rt.join("chat:34:42")
chat.on("message") { message, meta -> show(message, meta.from) }
val ack = chat.emitWithAck("message", buildJsonObject { put("text", "Hi") })
chat.volatile.emit("typing", buildJsonObject { put("on", true) })
rt.online.watch(listOf("34")).on("change") { change -> dot(change) }
rt.on("notice") { notice, _ -> toast(notice) }
```

The app's server answers the routes as every server package does (`{endpoint}/auth`, `/keys`): the same Express,
Laravel or Django server its web pages use. The connection goes straight to the realtime nodes and moves between them
without a drop, as the browser's does, and on Android keeps the nodes between launches in the app's cache folder, or where `nodeStore` says.

The API demo as a Jetpack Compose app, and the end-to-end proof with a browser on the other side, are in
[`example/`](example/README.md).

## Calls, and their screen

Calls are an Android library of their own, `cloud.wordplus:realtime-calls` ([`calls/`](calls)), so an app that only
uses rooms carries no WebRTC: calls between two people (direct, through TURN when they must, or in a room when neither
works), group calls, and someone rung into a call. Their screen is another, `cloud.wordplus:realtime-ui`
([`ui/`](ui)): the native call screen of Better Messages (the ring, the call between two, the group call's grid and the
controls), the same files as its app, so it improves with it. Both are optional. An app that draws its own
screen takes the calls alone.

```kotlin
CallScreen.configure(CallScreen.Appearance(strings = mapOf("end_call" to "Anruf beenden")))
CallScreen.attach(context, rt.calls(context))     // rings for every call coming in, and shows it once answered
val call = rt.startCall(context, callToken)       // a token from a route of your server, which checked first
CallScreen.show(context, call, name = "Ann")      // a call placed from the app
CallScreen.show(context, rt.joinGroupCall(context, meetingToken), title = "Daily meeting")
CallScreen.onAnswered = { answered -> … }         // the call a ring became, for the app to follow
```

The screen is an activity of its own over the app. `CallScreen.hide()` puts it away and the call goes on, from a button of
yours in its dock, and `CallScreen.restore()` brings it back. The app asks for the microphone and camera as it would for its own screen. One
not yet allowed when a call is answered is asked for then. `CallScreen.addPerson = true` puts Add Person in the dock.

The same module has who is online as the web draws it, `OnlineList`, `PersonRow`, `RowAction`, `OnlineCount`, `Avatar` and
`ActivityIndicator`, from what the app gives them, with no room behind them ([the views](docs/android/presence.md#the-views)).

## Docs

The Android guide, on the web with every other: [docs.wordplus.cloud/android](https://docs.wordplus.cloud/android/getting-started/). The API reference is Dokka's, from the KDoc and each library's `Module.md`, at [docs.wordplus.cloud/reference/android](https://docs.wordplus.cloud/reference/android/realtime/); `./gradlew dokkaGenerate` in a library's folder builds it into `build/dokka/html`.

[Getting started](docs/android/getting-started.md) · [rooms](docs/android/rooms.md) · [presence and online status](docs/android/presence.md) · [sending from your server](docs/android/sending.md) · [events from the app](docs/android/events.md) · [calls](docs/android/calls.md) · [encryption and your users](docs/android/encryption.md) · [customizing the screens](docs/android/customizing.md) · [shipping](docs/android/shipping.md) · [the example app](docs/android/example.md). For coding agents: [the skill](skills/wordplus-realtime-android/SKILL.md).

## Tests

```bash
./gradlew test                               # the conformance vectors
# with the API demo and two realtime nodes (in realtime-js: node test/native/serve.mjs):
WORDPLUS_SHOP=http://localhost:17533 WORDPLUS_CONTROL=http://127.0.0.1:17553 ./gradlew test
(cd calls && ./gradlew connectedDebugAndroidTest)  # the state of a stream, the ring of the system and the codecs of the rooms, with no call
(cd ui && ./gradlew connectedDebugAndroidTest)  # the call screen's pieces, on a device or an emulator with no call
(cd ui-tools && ./gradlew test)                 # the Gradle plugin that copies the screen into an app
(cd rtc-client && ./gradlew publishAllPublicationsToCheckRepository)  # what a release publishes of the room client
```

The calls run on the WordPlus RTC client for Android (`vendor/rtc-client-android`, LiveKit's Android SDK with WordPlus
Mesh's patch). `scripts/rtc-client.sh <a build of it>` copies a new one in.

A group call sends what the media server's settings for its clients say (`GroupShapes`, the client's `MeshSettings`),
as the browsers do: the microphone's bitrate, RED (never into an encrypted room), DTX and processing, the camera's
capture, codecs, top layer and ladder, the tallest it goes out at in software, the connect's timeouts, reconnect waits
and join budget. Where the server says nothing, the defaults of the settings, the values browsers use. Its join names the
module (`realtime-kotlin` and `GroupShapes.VERSION`, which a release keeps in step with `calls/build.gradle.kts`'s).

MIT licence.

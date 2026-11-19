# Shipping

## Your server address

Builds point at your server in full, over HTTPS: Android refuses plain HTTP from API 28. While you develop, the emulator reaches your computer at `10.0.2.2` (or at `localhost` after `adb reverse tcp:3000 tcp:3000`). Allow it in a network security config for debug builds only:

```xml
<!-- res/xml/network_security_config.xml, named by android:networkSecurityConfig in a debug manifest -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

Your app in WordPlus Cloud needs no address for the app: a native app is let in by itself. The key and secret stay on your server. The app gets what it needs from your server `config()` at sign-in.

## Permissions

The library manifests declare what they use, and Gradle merges them into the manifest of your app:

| Library | Permissions |
|---|---|
| `realtime` | `INTERNET` (yours to declare: a plain Kotlin library has no manifest) |
| `realtime-calls` | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `RECORD_AUDIO`, `CAMERA`, `MODIFY_AUDIO_SETTINGS`, and for `SystemCalls` `MANAGE_OWN_CALLS`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK`, `FOREGROUND_SERVICE` with its `PHONE_CALL`, `MICROPHONE`, `CAMERA` and `MEDIA_PROJECTION` types, the foreground services of a call and of a shared screen, the receiver of Decline and Hang up, and the ring screen |
| `realtime-ui` | the call screen activity, which shows on the lock screen and supports picture in picture |

`RECORD_AUDIO` and `CAMERA` are asked for at run time: the call screen asks when a call is answered, and an app of its own screens asks before a call ([calls](calls.md#calls-of-your-own)). `POST_NOTIFICATIONS` too, from Android 13: without it a call rings only with the app open. The emulator camera is a test pattern: try video calls on a device.

## In the background

- **Rooms:** Android may stop the app network soon after it leaves the screen, and ends its process when it needs the memory. Coming back, the SDK connects again and each room gets what it missed from its history, so nothing your rooms keep is lost.
- **A call going on** stays on while its screen is in picture in picture. With `SystemCalls` started, it goes on with the app off the screen too: the SDK runs a foreground service of the `phoneCall` type while a call is on. Without `SystemCalls`, run one of your own, of type `microphone` (and `camera` for video), with `FOREGROUND_SERVICE_MICROPHONE` and `FOREGROUND_SERVICE_CAMERA` from Android 14.
- **Calls ring a closed app** with `SystemCalls`, through a high-priority Firebase message (below).

## Push and Telecom

What a call ringing a locked phone and a closed app needs ([calls](calls.md#ringing-a-locked-phone)):

1. **Firebase in the app:** add the app to a Firebase project, put its `google-services.json` in the app module, and hand the call's messages to `SystemCalls` from your `FirebaseMessagingService`, as the calls page shows.
2. **A service account:** in Firebase, open **Project settings**, then **Service accounts**, and choose **Generate new private key**. It downloads a JSON file.
3. **On the account's Apps page,** open **Phone calls** for the app and give that JSON file. WordPlus Cloud keeps it encrypted, checks it with Google at once, and the page says when Google refused a message.

- **Google Play** asks an app holding `USE_FULL_SCREEN_INTENT` and the `phoneCall` foreground service type to declare calling as a core function of the app, in the Play Console. A listing that does not lets Android 14 take the ring over a locked screen away (`SystemCalls.canRingFullScreen`).

## Google Play

- **The data safety form** describes what your app collects, as for any of its features: the SDK sends WordPlus Cloud the user id, the rooms they join, and the data your app sends, sealed with keys WordPlus Cloud does not hold, over TLS. The history of a room keeps that sealed data for as long as its rule says. It shares nothing with advertisers and tracks nothing.
- **Foreground service types:** a target of Android 14 or later declares each it uses in the Play Console. The SDK brings `phoneCall` with `SystemCalls`, and `mediaProjection` with sharing the screen. An app that never shares may take that one out of its manifest with `tools:node="remove"`: the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission, and the services `cloud.wordplus.realtime.calls.ScreenShareService` and `io.livekit.android.room.track.screencapture.ScreenCaptureService`.
- **Encryption:** rooms are sealed with XChaCha20-Poly1305, calls with the WebRTC DTLS-SRTP and, in group calls, the LiveKit end-to-end frame encryption.

## R8

The libraries need no R8 rules of your own: nothing in them is reached by reflection, and WebRTC brings its own. Your `@Serializable` types for `eventsOf<T>()` need the kotlinx.serialization Gradle plugin, as any serialization does.

## Versions

The calls run on the WordPlus RTC client for Android (`cloud.wordplus:rtc-client-android`), which is LiveKit's Android SDK 2.29.1 with WordPlus Mesh's changes, as the Better Messages app runs it. They take LiveKit's WebRTC build at the version that app is tested with. The room client keeps LiveKit's own packages (`io.livekit.android`), so an app can't also add `io.livekit:livekit-android`. An app that draws with LiveKit uses the room client instead.

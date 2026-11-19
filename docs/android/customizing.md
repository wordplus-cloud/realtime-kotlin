# Customizing the screens on Android

The call screen (`cloud.wordplus:realtime-ui`) is the Better Messages native one. Your app can change it as far as it needs to, in three steps, each going further than the last, with your AI coding agent or by hand. Or draw a screen of its own on the calls alone.

## 1. The look

```kotlin
CallScreen.configure(CallScreen.Appearance(
    strings = mapOf("end_call" to "Hang up", "calling" to "Ringing…"),
    tokens = mapOf("--wplus-color-accent" to "37, 99, 235"),       // red, green, blue, or a hex value
    tokensDark = mapOf("--wplus-color-accent" to "96, 165, 250"),
))
```

| Token | What it colours |
|---|---|
| `--wplus-color-accent`, `--wplus-color-text-on-accent` | a control that is on, and what is drawn on it |
| `--wplus-color-success`, `--wplus-color-recording`, `--wplus-color-warning`, `--wplus-color-online` | answering, hanging up and a microphone off, a warning, the online dot |
| `--wplus-call-canvas` | the stage, near-black unless you set it: a camera picture is seen against it |
| `--wplus-call-tile` | the tiles of a group call |
| `--wplus-call-btn` | each control in the dock |
| `--wplus-call-dock` | the glass behind the dock |

The same names as the web call screen, so one palette serves both. Their names before, `--bm-…`, still work.

## 2. A piece of your own

Replace one piece and keep the rest of the screen:

```kotlin
CallScreen.pieces = CallScreen.Pieces(
    // The buttons of the dock: ours by id, in their order, and your own. Hanging up stays last.
    controls = { context, ours, moment ->
        ours.filter { it.id != "pip" } +
            CallScreen.CallControl.own(context, "order", "Show the order", CallScreen.CallControl.icons.getValue("alert")) { showOrder() }
    },
    // The line under the name, null keeps ours.
    status = { moment -> if (moment.phase == "ringing_out") "Ringing Ann…" else null },
    // Your own view while a call rings in, full screen: answer() shows the call on our screen.
    ringing = { context, ring -> RingView(context, ring.name, ring.answer, ring.decline) },
    // A view over the tile of each person in a group call, such as a badge: the same view again stays.
    tile = { context, person -> if (person.isMe) youBadge(context) else null },
    // A view over a call between two, under the dock.
    stage = { _, _ -> null },
)
```

| Piece | What it is |
|---|---|
| `controls` | the dock buttons. Ours, by id, as the screen shows them: `answer` (a call ringing in), `settings` (once the call is up: Device Settings, the camera, the microphone and the speaker with a test of each, and what a call between two has sent and received), `flip` (while the camera is on), `camera`, `mute`, `speaker` (once the call is answered), `share` (once the call is up, [sharing the screen](calls.md#sharing-the-screen)), `add` (once the call is up, with `CallScreen.addPerson` on, [Add Person](calls.md#on-the-call-screen)), `pip` (picture in picture: in a video call between two, in a group call by video, and in a group call by voice while someone else shares a screen), `host` (Host Controls, for a moderator in a group call) and `end`. `CallControl.own(context, id, label, icon, latched) { … }` adds yours, drawn as ours are (`icon` a path in a 24 grid, as the Lucide icons are) |
| `status` | the line under the name: `moment.phase` is `ringing_out`, `ringing_in`, `connecting`, `in_progress` or `reconnecting`, `moment.seconds` how long the call has been on |
| `ringing` | your own view for a call ringing in, over the lock screen as ours is |
| `tile` | a view over the tile of someone in a group call. `person` has `identity`, `name`, `avatar`, `isMe`, `speaking` and `muted` |
| `stage` | a view over a call between two |

The dock has no raise hand button: a group call of the SDK has no way to send one, on the web either. A listener asks through a room of yours ([speakers and listeners](calls.md#speakers-and-listeners)), and a control of your own in `controls` can send it.

## 3. The code itself

Copy the screen into your app module and change anything in it. Only the screen is copied: the calls under it stay in `cloud.wordplus:realtime-calls`, and keep getting their fixes.

```kotlin
// app/build.gradle.kts
plugins {
    id("cloud.wordplus.realtime-ui") version "1.0.0"
}
```

```bash
./gradlew :app:wordplusUiAdd
```

- **Where it goes:** `src/main/kotlin`, in the packages of the screen: `cloud/wordplus/realtime/ui/calls/CallsStage.kt` (the call), `CallsGrid.kt` (the grid of a group call), `CallsActivity.kt` and the rest, `cloud/wordplus/realtime/ui/CallScreen.kt`, which drives them from the calls, and `CallScreenPreviewActivity.kt` ([checking a change](#checking-a-change)) (`--dir` for another folder).
- **Its tests** go into `src/androidTest/kotlin`.
- **Then, in the module:** take `cloud.wordplus:realtime-ui` out of its dependencies, since your copy is the screen now. Keep `cloud.wordplus:realtime-calls`, add `cloud.wordplus:rtc-client-android` (the room client of the calls) and `androidx.core:core-ktx`, and declare the screen activities in your manifest, as the task prints them. `CallScreen.attach(context, calls)` works as before.
- **Your own screen, from scratch:** `GroupVideoView` draws the camera or shared screen of someone in a group call (`show(person.identity, call)`), or your own camera (`showOwn(call)`). `CallVideoView` does the same in a call between two.

### When ours changes

Nothing changes your copy by itself.

1. `./gradlew :app:wordplusUiDiff` shows what changed in ours since you copied it, file by file, and whether you changed that file too.
2. `./gradlew :app:wordplusUiUpdate` takes ours into every file you never changed.
3. Merge the rest by hand, then build and try a call.
4. `./gradlew :app:wordplusUiAccept` records that yours has ours.

`wordplus-ui.json` and `.wordplus-ui/` in the module record what you copied. Commit both.

## Checking a change

- **Every state, with no call:** `CallScreen.preview(context)` lists the states: calling, ringing in, connecting, in a call by voice and by video, with the microphone and camera off, with the other side lost, reconnecting, in a group call by video and by voice, and with Add Person open. Its Dark and RTL buttons switch the look. `CallScreen.preview(context, CallScreenPreviewActivity.Shown.VIDEO)` opens one state. It draws your pieces and your look, and your copy once you have one.
- **From a command line** (a script, or an AI agent): the preview is not exported, so let debug builds open it from adb. In `app/src/debug/AndroidManifest.xml`:

  ```xml
  <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
      <application>
          <activity android:name="cloud.wordplus.realtime.ui.CallScreenPreviewActivity" android:exported="true" tools:replace="android:exported" />
      </application>
  </manifest>
  ```

  Then open a state and take a screenshot:

  ```bash
  adb shell am start -n <applicationId>/cloud.wordplus.realtime.ui.CallScreenPreviewActivity --es cloud.wordplus.realtime.ui.preview.shown VIDEO
  adb exec-out screencap -p > video.png
  ```

  Opened this way, it draws the pieces the app has set by then. Set them in `onCreate` of your `Application` to see them here.
- **The tests:** with a copy, run `./gradlew connectedDebugAndroidTest` on a device or an emulator after each change. The tests check each state of the preview, the dock buttons and their labels, and your pieces. The module needs `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` and `androidx.test.ext:junit`, `androidx.test:runner` and `androidx.test:core` as `androidTestImplementation`.
- **Then a real call,** both ways, and a group call.

## What must keep working

- **Every call state:** calling out, ringing in, connecting, in a call, reconnecting, ended, and a group call.
- **A camera or a microphone refused:** the call goes on, and says why.
- **TalkBack:** every button keeps its label.
- **Picture in picture**, the lock screen, and the screen put away and brought back.
- **Right to left**, and a small phone and a tablet.

# The example app

The API demo of every other guide, as a Jetpack Compose app: sign in as Ann (support), Ben (a customer) or Cara (the warehouse), and the app joins the same rooms as the shop web pages, so the app and a browser signed in as someone else see each other.

| What it shows | How |
|---|---|
| The order of Ben moving on, and a notice to Ben alone | the room `order:1042`, which the shop server emits to, as a flow. `rt.on("notice")` for what it sends Ben ([sending](sending.md)) |
| Who is in the shop, and who is online | the presence room `shop`, and `rt.online.watch` ([presence](presence.md)) |
| The chat of Ann and Ben, with typing and its history | `chat:34:42`: `emitWithAck` for a message, `volatile` for typing, `history` when the app opens ([events](events.md)) |
| Calls with anyone in the shop, adding the third, and the daily meeting | `calls.start`, `call.add`, `calls.group`, on the Better Messages call screen ([calls](calls.md)) |
| Calls that ring the app closed, with the call notification and Telecom | `SystemCalls.start` in `ShopApplication.kt`, the connection of whoever signed in last ([ringing a locked phone](calls.md#ringing-a-locked-phone)) |
| The live show of Ann: its picture, how many watch, and its chat | `calls(context).stream("show:mugs")`, `LiveStreamVideoView` ([live streams](streams.md)) |

Its server is that of the Express example (realtime-js, `examples/express`), which signs the app in with `POST /api/token` and answers the realtime routes for its token, beside the shop web pages.

## The files

- `example/app/src/main/kotlin/…/ShopApplication.kt`: the app, which holds the shop and starts `SystemCalls`.
- `example/app/src/main/kotlin/…/Shop.kt`: signing in, the connection, the rooms and the calls, the app's own rather than a screen's, so a call that rings the app closed finds it.
- `example/app/src/main/kotlin/…/ShopScreen.kt`: the screens, with a bar of its own while the call screen is put away.
- `example/app/src/androidTest/kotlin/…/ProofTest.kt`: the end-to-end test.

## Running it

```bash
cd realtime-js/examples/express && npm install && npm start   # the shop at http://localhost:3000
```

Open `example/` in Android Studio and run it on an emulator, which reaches the shop at `http://10.0.2.2:3000`. For a shop elsewhere, `adb shell am start -n cloud.wordplus.exampleshop/.MainActivity --es shop <address>`. Sign in as someone else at the same address in a browser.

For a call that rings the app closed, add an Android app with the package `cloud.wordplus.exampleshop` to a Firebase project of yours, put its `google-services.json` in `example/app/` (git leaves it out), and its service account on the shop's app in WordPlus Cloud ([push and Telecom](shipping.md#push-and-telecom)). The shop server runs with that app's key and secret, and the emulator needs a Google Play image.

## Tested end to end

`node test/native/proof.mjs android`, in realtime-js, starts the shop and two realtime servers, signs Ann and Cara in on the shop page in Chromium, starts an emulator when none is attached, and runs `ProofTest` as Ben:

- the order moving on, with the notice to Ben.
- presence and online status, both ways.
- typing and messages, both ways, and the chat from history after the app opens again.
- the app realtime server draining while the order moves on, with nothing lost.
- a call between the browser of Ann and the app, both ways, through the call screen, Cara added to it, and the daily meeting.
- a call from Ann ringing the app closed: the push (the FCM data the app's messaging service hands `SystemCalls.push`, handed in by the test), the call notification, Answer on it, the app opening on the call screen with the call connected.
- the live show of Ann from her browser: its picture in the app, the app counted among those watching, the chat both ways, and its end.

# The API demo for Android

The API demo as a Jetpack Compose app on `cloud.wordplus:realtime`: sign in as Ann (support), Ben (a customer) or
Cara (the warehouse), then watch the order of Ben move on, see who is in the shop and who is online, and chat with typing. It
joins the same rooms as the shop web pages, so the app and a browser signed in as someone else see each other. Calls
between them and the daily meeting run on `cloud.wordplus:realtime-calls`, drawn by the Better Messages call screen
(`cloud.wordplus:realtime-ui`).

- `app/src/main/kotlin/…/Shop.kt`: signing in, the connection and the rooms (the order, presence, online status, the
  chat with acks, typing and history), as a ViewModel whose events come as flows.
- `app/src/main/kotlin/…/ShopScreen.kt`: the screens.
- `app/src/androidTest/kotlin/…/ProofTest.kt`: the end-to-end test, with a browser on the other side.

The SDK comes from this checkout (`includeBuild("..")` in `settings.gradle.kts`). An app of your own takes it from Maven
Central.

## Running it

The app server is the Express example in realtime-js (`examples/express`), which signs the app in with
`POST /api/token` and answers its realtime routes. Locally, with a realtime server of your own:

```bash
cd ../../realtime-js && node test/native/serve.mjs   # the shop at http://localhost:17533, two realtime nodes
adb reverse tcp:17533 tcp:17533 && adb reverse tcp:17512 tcp:17512
adb shell am start -n cloud.wordplus.exampleshop/.MainActivity --es shop http://localhost:17533
```

Without `--es shop`, the app expects the Express example `npm start` at `http://10.0.2.2:3000`. Sign in as someone
else at the same address in a browser.

## The proof

```bash
cd ../../realtime-js && node test/native/proof.mjs android
```

It starts the shop and the nodes, signs Ann in on the shop page in Chromium, starts an emulator when none is attached,
and runs `ProofTest` as Ben: the order moving on with the notice of Ben, presence and online status both ways, typing and
messages both ways, the chat from history after the app opens again, and the app node draining while the order moves
on.

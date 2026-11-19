# Module realtime-calls

Calls between two people and group calls on WordPlus Cloud, which ring on every page and app the callee has open: a phone and a browser call each other.

Calls run directly between the two, through TURN when a network needs it, or in a room on WordPlus Cloud's media servers, and become a group call when either side adds someone. Your server decides who may call whom. Better Messages' call screen, in `realtime-ui`, draws them; this library is for an app that draws its own. The [calls guide](https://docs.wordplus.cloud/android/calls/) has more.

```kotlin
val calls = rt.calls(context)
calls.onIncoming { incoming -> scope.launch { show(incoming.accept()) } }
val call = calls.startCall(callToken)   // a token from a route of your server, which checked first
call.onState { state -> label = state.wire }
```

# Package cloud.wordplus.realtime.calls

Calls (`RealtimeCalls`, `Call`, `IncomingCall`), calls on signalling of the app's own (`CallTransport`, Better Messages' `P2PCallTransport`), group calls (`GroupCall`, `GroupPerson`, `CallInvite`) and their video (`CallVideoView`).

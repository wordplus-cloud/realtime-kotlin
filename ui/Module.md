# Module realtime-ui

The native call screen of Better Messages on WordPlus Cloud's calls: the ring, the call between two, the group call's grid and the controls, with no screen code of yours.

The screen is the same files as the Better Messages app, so it improves with it. It is an activity of its own over the app, and its chevron puts it away while the call goes on. The [calls guide](https://docs.wordplus.cloud/android/calls/#the-call-screen) has more.

```kotlin
CallScreen.attach(context, rt.calls(context))
val call = rt.startCall(context, callToken)   // a token from a route of your server, which checked first
CallScreen.show(context, call, name = "Ann")
```

# Package cloud.wordplus.realtime.ui

`CallScreen`, which drives the call screen from the calls, and who is online as the web draws it, from what you give them with no room behind them: `OnlineList`, `PersonRow`, `RowAction`, `OnlineCount`, `Avatar`, `PresenceDot`, `TypingDots` and `ActivityIndicator`.

# Module realtime

Rooms, events with acks, presence, counts, history, who is online and what your server sends the user, through WordPlus Cloud, sealed end to end. A plain Kotlin library, which Android apps take as it is.

The app joins rooms your server declares, with your server's routes and the token it signed in with: the same rooms as your web pages, so the app and a browser see each other. The [Android guide](https://docs.wordplus.cloud/android/getting-started/) walks through it.

```kotlin
val rt = Realtime(RealtimeOptions.fromConfig(config, server,
    headers = { mapOf("Authorization" to "Bearer $token") },
    callbackExecutor = ContextCompat.getMainExecutor(context)))
rt.connect()

val chat = rt.join("chat:34:42")
chat.on("message") { data, meta -> show(data, meta.from) }
val ack = chat.emitWithAck("message", buildJsonObject { put("text", "Hi") })
```

# Package cloud.wordplus.realtime

The connection (`Realtime`, `RealtimeOptions`), rooms (`Room`), who is online (`Online`), the keys (`Keyring`) and the node cache (`NodeStore`, `TextNodeStore`).

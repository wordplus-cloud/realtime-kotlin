# Getting started with Android

Live updates, chat, who is online and calls in an Android app written in Kotlin, through WordPlus Cloud. The app joins the same rooms as your web pages, so a customer in the app and an agent in a browser see each other, and a phone and a browser call each other. Your server stays the one your website has ([Next.js](https://docs.wordplus.cloud/nextjs/getting-started/), [Laravel](https://docs.wordplus.cloud/laravel/getting-started/), [Django](https://docs.wordplus.cloud/django/getting-started/), [Express](https://docs.wordplus.cloud/express/getting-started/) …): it declares the rooms, says who may join and call, and sends.

The SDK comes as three libraries, and an app takes only what it uses:

| Library | What it gives | Needs |
|---|---|---|
| `cloud.wordplus:realtime` | rooms, events with acks, presence, counts, history, who is online, what your server sends the user, the directory | a plain Kotlin library: Android 7 (API 24) and later, or any JVM |
| `cloud.wordplus:realtime-calls` | calls between two people, group calls, ringing someone into a call, live streams | WebRTC and LiveKit, which it brings |
| `cloud.wordplus:realtime-ui` | The Better Messages call screen: the ring, the call, the group call grid and the controls. And the live stream screen | Android |

The names are those of the browser: `join`, `on`, `emit`, `emitWithAck`, `volatile`, `history`, `members`, `count`, `online`, `calls.start`, `calls.group`, `calls.stream`. Events also come as flows. The [example app](example.md) is the shop of every other guide, in Jetpack Compose.

## 1. Your app in WordPlus Cloud

**Apps come with a subscription.** [Choose your connections](https://www.wordplus.cloud/pricing#apps), starting from 1,000, and your WordPlus Cloud account gets its **Apps** page.

Use the app you made for your website (**Apps** in your WordPlus Cloud account), so the app and the site share rooms and users. A native app has no address to list: it is let in by itself.

## 2. Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // for calls: their room client, LiveKit's Android SDK, takes a few artifacts from JitPack
    }
}

// app/build.gradle.kts
dependencies {
    implementation("cloud.wordplus:realtime:1.0.0")
    implementation("cloud.wordplus:realtime-calls:1.0.0")   // for calls
    implementation("cloud.wordplus:realtime-ui:1.0.0")      // for the Better Messages call screen
}
```

The libraries are built for Java 17, which the Android Gradle plugin 8 takes as it is.

## 3. Your server knows the app user

An app has no cookie of your site, so it sends its own sign-in with every request, such as a bearer token, and your server routes read the user from it. When the app signs in, send it the user realtime settings too, so it connects with no request first:

**Node**

```ts
// Express: the user of the session in a browser, that of the token in an app.
app.use( '/api/realtime', realtime.node( ( req ) => req.session.userId ?? userFromToken( req.get( 'authorization' ) ) ) );

app.post( '/api/token', express.json(), async ( req, res ) => {
    const user = await checkSignIn( req.body );
    res.json( { token: tokenFor( user ), realtime: await realtime.config( user.id ) } );
} );
```

**PHP**

```php
// Laravel: the package routes take $request->user(). Add the middleware that signs in the tokens of your app to
// 'routes' => [ 'middleware' => [ … ] ] in config/wordplus-realtime.php. Then, at sign-in:
return [ 'token' => $user->createToken( 'app' )->plainTextToken, 'realtime' => Realtime::config( $user->id ) ];

// Plain PHP: the user of the token to respond()
$realtime->respond( $action, user_from_token( $_SERVER['HTTP_AUTHORIZATION'] ?? '' ) );
```

**Python**

```python
# Django: who the user is, by your own function
path("api/realtime/", include(wr.urls(realtime, user=lambda request: user_from_token(request.headers.get("Authorization")))))

# FastAPI: current_user reads the token
app.include_router(wr.router(realtime, current_user), prefix="/api/realtime")

# At sign-in, in any of them
return {"token": token_for(user), "realtime": realtime.config(user.id)}
```

## 4. Connect

```kotlin
import cloud.wordplus.realtime.Realtime
import cloud.wordplus.realtime.RealtimeOptions
import cloud.wordplus.realtime.TextNodeStore

val server = "https://shop.example.com"
val (token, config) = signIn()   // your own sign-in, answering { token, realtime }
val prefs = context.getSharedPreferences("realtime", Context.MODE_PRIVATE)

val rt = Realtime(
    RealtimeOptions.fromConfig(
        config, server,
        headers = { mapOf("Authorization" to "Bearer $token") },
        callbackExecutor = ContextCompat.getMainExecutor(context),    // handlers on the main thread
        nodeStore = TextNodeStore({ prefs.getString(it, null) }, { key, value -> prefs.edit().putString(key, value).apply() }),
    ),
)
rt.connect()

val order = rt.join("order:1042")
order.on("updated") { data, _ -> status = data.jsonObject["status"]?.jsonPrimitive?.content ?: status }
order.on("error") { error, _ -> Log.w("shop", error.toString()) }   // forbidden, no_rule …
```

- **`RealtimeOptions.fromConfig`** takes your server `config()` as it came (a `JsonElement`), its routes resolved against your server address. Without one, give `RealtimeOptions(app = …, endpoint = "https://shop.example.com/api/realtime", headers = { … })`, and the app asks your keys route first. A route at an address of its own goes in `routes`, in full: `routes = mapOf("calls" to "https://shop.example.com/check-call")`. From `config()` it comes by itself.
- **Handlers run on `callbackExecutor`**, a thread of the SDK unless you give one: give the executor of the main thread to update your views from them.
- **`nodeStore`** keeps the realtime servers the app reached between launches, so it goes straight back to one. Without it they are kept in a file of the app's cache folder, which the system may clear, so give it only to keep them elsewhere.
- **One `Realtime` an app**, kept while the user is signed in (in a ViewModel or an object of your app). `rt.close()` at sign-out.
- **The connection keeps itself:** through a drop, from one realtime server to another without one, and back after the app was in the background, every room joined again with what it missed from its history.
- **Another namespace of the realtime server:** `namespace` and `auth` in `RealtimeOptions` open the connection on another product's namespace with its own handshake, such as Better Messages' `/` for its calls ([calls on your own signalling](calls.md#calls-on-your-own-signalling)). It reconnects, moves between servers and keeps the servers it knows there as on `/channels`, which it opens when you name none. Rooms, presence and calls by name are `/channels`' own.

## 5. Send

Your server sends to the room, and the app handler runs:

**Node**

```ts
await realtime.to( 'order:1042' ).emit( 'updated', { status: 'shipped' } );
```

**PHP**

```php
$realtime->to( 'order:1042' )->emit( 'updated', [ 'status' => 'shipped' ] );   // Laravel: broadcast( new OrderUpdated( $order ) )
```

**Python**

```python
realtime.to("order:1042").emit("updated", {"status": "shipped"})
```

## The connection

The SDK connects, and keeps the connection, by itself. The app may show how it is, and hear what the realtime servers say of it:

```kotlin
rt.state                                    // ConnectionState.Connecting, Connected, Disconnected or Closed
rt.onState { state, reason ->               // each change, on callbackExecutor. reason: why it dropped, for your log
    offlineBar.isVisible = state != ConnectionState.Connected
}
rt.on("error") { error, _ -> Log.w("shop", error.toString()) }   // the connection refused, such as not_licensed
rt.on("moved") { _, _ -> }                  // now on another realtime server, with no drop
rt.on("signed_out") { _, _ -> showSignIn() }   // your server signed the user out
```

| State or event | When |
|---|---|
| `Disconnected` | the connection dropped. The SDK connects again by itself, after about 1, 2, 5 and 10 seconds, then every 20, and each room gets what it missed from its history |
| `Closed` | after `rt.close()`: nothing connects again |
| `error` | the realtime servers refused the connection, by its code, such as a wrong app key (`not_licensed`). The SDK tries again as after a drop ([errors](https://docs.wordplus.cloud/apps/errors/#the-connection)). A room that refuses the app says so on the room ([rooms](rooms.md#joining)) |
| `moved` | the connection moved to another realtime server without a drop, every room with it. Nothing for the app to do |
| `signed_out` | your server signed the user out of everything ([sending](sending.md#signed-out-by-your-server)) |

## Next

- [Rooms](rooms.md), [presence and online status](presence.md), [sending from your server](sending.md) and [events from the app](events.md)
- [Calls](calls.md), with the Better Messages call screen, and [live streams](streams.md)
- [Encryption and your users](encryption.md)
- [Customizing the screens](customizing.md): the call screen look, a piece of your own, or its whole code
- [Shipping](shipping.md), and [the example app](example.md)
- The API reference: [realtime](https://docs.wordplus.cloud/reference/android/realtime/), [realtime-calls](https://docs.wordplus.cloud/reference/android/realtime-calls/) and [realtime-ui](https://docs.wordplus.cloud/reference/android/realtime-ui/)
- On your server side: [rooms in depth](https://docs.wordplus.cloud/apps/rooms/), [errors](https://docs.wordplus.cloud/apps/errors/) and [limits](https://docs.wordplus.cloud/apps/limits/)

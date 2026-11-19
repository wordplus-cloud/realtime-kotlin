# Sending from your server

Your server sends to rooms and to users with its secret, the same way whether a browser or an app gets it ([sending in depth](https://docs.wordplus.cloud/apps/sending/)). What people build with it: [live updates and notifications](https://docs.wordplus.cloud/use-cases/live-updates/).

## To a room

**Node**

```ts
await realtime.to( 'order:1042' ).emit( 'updated', { status: 'shipped' } );
```

**PHP**

```php
$realtime->to( 'order:1042' )->emit( 'updated', [ 'status' => 'shipped' ] );
```

**Python**

```python
realtime.to("order:1042").emit("updated", {"status": "shipped"})
```

```kotlin
rt.join("order:1042").on("updated") { data, _ -> status = data.jsonObject["status"]?.jsonPrimitive?.content ?: status }
```

## Not back to the app that caused it

When the app asks your server to change something, it sends its own name on the connection, `rt.tab`, and your server leaves it out of the emit: the app has already shown the change.

```kotlin
val request = Request.Builder()
    .url("$server/orders/1042/status")
    .header("Authorization", "Bearer $token")
    .header("X-Realtime-Tab", rt.tab)
    .post(buildJsonObject { put("status", "shipped") }.toString().toRequestBody("application/json".toMediaType()))
    .build()
```

**Node**

```ts
await realtime.to( 'order:1042' ).except( req.get( 'x-realtime-tab' ) ).emit( 'updated', { status } );
```

**PHP**

```php
$realtime->to( 'order:1042' )->except( $_SERVER['HTTP_X_REALTIME_TAB'] ?? null )->emit( 'updated', [ 'status' => $status ] );
// Laravel: broadcast( new OrderUpdated( $order ) )->toOthers();
```

**Python**

```python
realtime.to("order:1042").except_tab(request.headers.get("X-Realtime-Tab")).emit("updated", {"status": status})
```

## To a user

What only one user should get, on every page and app they have open, without a room of yours:

**Node**

```ts
await realtime.toUser( 42 ).emit( 'notice', { text: 'Your order shipped' } );
```

**PHP**

```php
$realtime->toUser( 42 )->emit( 'notice', [ 'text' => 'Your order shipped' ] );
```

**Python**

```python
realtime.to_user(42).emit("notice", {"text": "Your order shipped"})
```

```kotlin
rt.on("notice") { data, _ -> toast(data.jsonObject["text"]?.jsonPrimitive?.content ?: "") }
viewModelScope.launch { rt.events("notice").collect { (notice, _) -> toast(notice.toString()) } }   // or as a flow
```

It reaches the app while it is connected. The SDK pushes calls alone ([ringing a locked phone](calls.md#ringing-a-locked-phone)): an app that is closed, or long in the background, hears what your server sent it next time it opens only if it was also kept in the history of a room.

## Asking the app

Your server asks the open pages and apps of a user, and waits for their replies ([a user](https://docs.wordplus.cloud/apps/sending/#a-user), and the use case [every page and app of a user](https://docs.wordplus.cloud/use-cases/devices/)):

**Node**

```ts
const replies = await realtime.user( 42 ).ask( 'cart', { why: 'checkout' } );   // [ { data, at } ]
```

**PHP**

```php
$replies = $realtime->user( 42 )->ask( 'cart', [ 'why' => 'checkout' ] );   // [ [ 'data' => …, 'at' => … ] ]
```

**Python**

```python
replies = realtime.user(42).ask("cart", {"why": "checkout"})   # [ { "data": …, "at": … } ]
```

The app answers the events it chooses, from a suspend function. The question and the reply are sealed with the key of the user:

```kotlin
rt.answer("cart") { data, _ -> JsonObject(mapOf("items" to JsonPrimitive(cart.count()))) }
```

## Signed out by your server

`realtime.user( 42 ).disconnect()` on your server takes the user out of everything at once. The app hears `signed_out`, then a second later asks your routes for new tokens and a new identity, so your rules decide whether the user comes back:

```kotlin
rt.on("signed_out") { _, _ -> showSignIn() }
```

## Webhooks

What apps and pages send to rooms, who comes online, and a live stream going live and ending reach your server by webhook, as for your web pages ([webhooks](https://docs.wordplus.cloud/apps/sending/#webhooks), [live streams](streams.md#webhooks)).

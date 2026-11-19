# Rooms

A room is a name the app joins to get its events: `order:1042`, `chat:34:42`, `shop`. Your server declares your rooms once, by pattern, with what each keeps and allows ([rooms in depth](https://docs.wordplus.cloud/apps/rooms/), with Node, PHP and Python side by side). Here the room of an order is named `order:` and its number, `order:1042`. The app asks your server to join it, and your rule answers for the user. This rule decides:

1. An order that does not exist opens for nobody.
2. Only the customer who placed it joins its room.
3. The room keeps its last 20 events (`history`), for an app that comes back after a drop.

**Node**

```ts
realtime.room( 'order:{id}', { history: 20 }, async ( user, { id } ) => {   // 3. The last 20 events kept.
    // 1. An order that does not exist opens for nobody.
    const order = await findOrder( id );
    if ( ! order ) return false;

    // 2. Only the customer who placed it.
    return order.userId === user;
} );
```

**PHP**

```php
$realtime->room( 'order:{id}', function ( $user, array $p ) {
    // 1. An order that does not exist opens for nobody.
    $order = find_order( $p['id'] );
    if ( null === $order ) {
        return false;
    }

    // 2. Only the customer who placed it.
    return $order->user_id === $user;
}, [ 'history' => 20 ] ); // 3. The last 20 events kept.
```

**Python**

```python
def may_see_order(user, p):
    # 1. An order that does not exist opens for nobody.
    order = find_order(p["id"])
    if order is None:
        return False

    # 2. Only the customer who placed it.
    return order.user_id == user


realtime.room("order:{id}", may_see_order, history=20)   # 3. The last 20 events kept.
```

The app asks your auth route for every room it joins in the same moment in one request, with the headers your `RealtimeOptions` give, and keeps each token until 10 minutes before it expires.

## Joining

```kotlin
val order = rt.join("order:1042")

order.on("updated") { data, meta ->                       // meta: from, id, at, replayed
    status = data.jsonObject["status"]?.jsonPrimitive?.content ?: status
}
order.on("joined") { _, _ -> live = true }
order.on("error") { error, _ -> Log.w("shop", error.toString()) }   // { code: forbidden, no_rule, full, expired … }

order.state           // RoomState.Joining, Joined, Failed, Left
order.ready()         // suspends until joined, throws its error
rt.leave("order:1042")   // or order.leave()
```

- **`join` gives the same room for the same name**, and `rt.room(name)` the one joined, or null.
- **A room of two people:** `rt.join("chat:{pair}", with = "34")`. `{pair}` is this user and the other, the smaller id first, so both of them name the same room, `chat:34:42`. `rt.room` and `rt.leave` take it too. It throws `no_identity` where your server named no user.
- **Events reach a handler opened:** you never handle sealed values. Data is the kotlinx.serialization `JsonElement`.
- **`meta.from`** is the user who sent it from a page or an app, as your server vouched for them, or null for your server. `meta.id` and `meta.at` are the kept event id and time.
- **The events of the room itself:** `joined`, `join` and `leave` (the members of a presence room), `count` (`{ n }`), `error` (`{ code }`) and `kicked`. Nobody can send them.
- **Many at once:** the rooms you join or leave in one moment go in one request, up to 100 a request, and so do the rejoins after a reconnect or a move. An inbox can join the rooms of its visible rows as they come.
- **`onAny`** hears every event of the room, `(event, data, meta)`. `once` hears the next one only. `off(listener)` removes a handler, and `off("event")` every handler of that event.

## As flows, decoded

```kotlin
@Serializable
data class OrderUpdate(val status: String)

viewModelScope.launch {
    order.eventsOf<OrderUpdate>("updated").collect { (update, meta) -> status = update.status }
}
```

`events(name)` gives the `JsonElement`s. `eventsOf<T>(name)` decodes each into your `@Serializable` type, leaving out fields it does not name, and skips one that does not decode. The handler goes when the flow collection ends.

## History, and nothing lost on a reconnect

A room keeps its events for 5 minutes at most, for recovery. Your app keeps its own records, such as the messages of a chat, from the [webhook](events.md#keeping-the-conversation).

- **A reconnect gets what it missed:** back within the history span, the app gets the events it missed first, in order, with `meta.replayed`, then the new ones. That holds after a drop, a move between servers, and the app coming back from the background.
- **Paging back,** oldest first, at most 100 a page:

```kotlin
val page = chat.history(limit = 50)                                       // the newest 50
val older = chat.history(before = page.events.firstOrNull()?.meta?.id, limit = 50)
for (event in page.events) show(event.event, event.data, event.meta)      // page.more: there is older
```

`history` throws `RealtimeException("no_history")` for a room that keeps none.

## Rooms anyone may join

A room your server declares `public` may be joined signed in or not: `config()` gives the app its grants (`grants` in `RealtimeOptions`), so it joins with no request to your server.

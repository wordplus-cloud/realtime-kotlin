# Presence and online status

Two things, both live: who is in a room now (presence), and whether a user is online anywhere in your app (online status), whatever page or app they have open. What people build with them: [who is online and who is here](https://docs.wordplus.cloud/use-cases/online/).

## A presence room

Your server declares the room with `presence: true`, and its rule answers with what the others see of each member ([presence in depth](https://docs.wordplus.cloud/apps/presence/)). Here the room of everyone in the shop is named `shop`. The app asks your server to join it, and the rule decides:

1. Everyone in it is listed, and each one sees the others come and go (`presence`).
2. Only signed-in people join: a visitor who is not signed in has no user.
3. The others see the role of each member, such as `support` or `customer`.

**Node**

```ts
realtime.room( 'shop', { presence: true }, async ( user ) => {   // 1. Everyone in it is listed.
    // 2. Signed-in people only.
    if ( user === null ) return false;

    // 3. What the others see of them: their role.
    return { info: { role: await roleOf( user ) } };
} );
```

**PHP**

```php
$realtime->room( 'shop', function ( $user ) {
    // 2. Signed-in people only.
    if ( null === $user ) {
        return false;
    }

    // 3. What the others see of them: their role.
    return [ 'info' => [ 'role' => role_of( $user ) ] ];
}, [ 'presence' => true ] ); // 1. Everyone in it is listed.
```

**Python**

```python
def shop_member(user):
    # 2. Signed-in people only.
    if user is None:
        return False

    # 3. What the others see of them: their role.
    return {"info": {"role": role_of(user)}}


realtime.room("shop", shop_member, presence=True)   # 1. Everyone in it is listed.
```

Without `info`, the info of a member is their profile from your server `profile`: their name and avatar.

```kotlin
val shop = rt.join("shop")
for (event in listOf("joined", "join", "leave")) {
    shop.on(event) { _, _ ->
        members = shop.members.map { (id, info) -> Member(id, info.jsonObject["name"]?.jsonPrimitive?.content ?: "") }
    }
}
```

- **`members`** is `Map<String, JsonElement>`, id to info: everyone listed when the app joined, kept up to date by `join` and `leave`. A user with the app open twice is one member.
- **Leaving** (`rt.leave("shop")`) takes the app out. The user leaves the list when their last page or app does. Joining again puts them back.
- **A large room** lists its first 1,000 members. `membersPage(cursor, limit)` pages through the rest.

## Counting

A room with `count: true` on your server, or any presence room, keeps `room.count` and sends `count` (`{ n }`) when it changes, at most once a second:

```kotlin
rt.join("product:7").on("count") { data, _ -> watching = data.jsonObject["n"]?.jsonPrimitive?.int ?: 0 }
```

## Who is online, across your app

A user is online while any page or app of theirs is connected with the identity your server gave it, and offline 10 seconds after the last one closes. An app sees online status only for a signed-in user.

```kotlin
val everyone = listOf("34", "42", "43")
rt.online.watch(everyone).on("change") {                   // { id, online, at? }: now, then every change
    online = everyone.filter { rt.online.has(it) }.toSet()
}
rt.online.lastSeen("43")                                    // when, in ms since 1970, for one offline
rt.online.on("count") { data -> showOnline(data.jsonObject["n"]?.jsonPrimitive?.int ?: 0) }
val (ids, next) = rt.online.list()                          // a page of who is online
```

- **Up to 1,000 users** watched a connection. `unwatch` stops. A watch refused or not answered is `on("error")`, with its `code`: `no_identity` for a user your server has not vouched for, `timeout` off line.
- **Your server** decides who is never shown online (`hidden`, for a "Hide my status" setting), and asks itself with `realtime.online.has( id )` ([who is online](https://docs.wordplus.cloud/apps/presence/#who-is-online-across-your-app)).

## Names and pictures

The app directory holds every signed-in user profile, from your server, sealed:

```kotlin
val people = rt.users(listOf("34", "43"))   // "34" to { user_id, name, avatar … }
title = people["34"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""
```

A user is in it once they connect, for a day after they last did. Ask again when someone comes online. Up to 100 a request.

## The views

`cloud.wordplus:realtime-ui` has the web's presence parts, under their names, as Android views: `OnlineList`, `PersonRow`, `RowAction`, `OnlineCount`, `Avatar`, `PresenceDot`, `TypingDots` and `ActivityIndicator`. They draw what you give them, and none of them reads a room or a connection, so you feed them from a room, from your own list, or from anything else:

```kotlin
import cloud.wordplus.realtime.ui.*

val shop = rt.join("shop")
val list = OnlineList(context, title = "In the shop")   // the title and count, a row each, the line for nobody

fun drawPeople() {
    val rows = shop.members.map { (id, info) ->
        val name = info.jsonObject["name"]?.jsonPrimitive?.content ?: ""
        PersonRow(
            context,
            avatar = Avatar(context, name, info.jsonObject["avatar"]?.jsonPrimitive?.contentOrNull, sizeDp = 32, presence = Presence.ONLINE, decorative = true),
            name = name,
            sub = if (id == rt.user) "You" else info.jsonObject["role"]?.jsonPrimitive?.contentOrNull,
            actions = if (id == rt.user) emptyList() else listOf(RowAction(context, RowAction.icons.getValue("phone"), "Call $name") { call(id) }),
        )
    }
    list.count = rows.size
    list.rows = rows
}
for (event in listOf("joined", "join", "leave")) shop.on(event) { _, _ -> drawPeople() }

val watching = OnlineCount(context)                     // nothing at 0, 99+ past 99
shop.on("count") { data, _ -> watching.count = data.jsonObject["n"]?.jsonPrimitive?.int ?: 0 }

val chat = rt.join("chat:34:42")
val typing = ActivityIndicator(context)                 // Anna typing, Anna, Bob typing, 3 typing
val writers = mutableMapOf<String, String>()
chat.on("typing") { data, meta ->
    val id = meta.from ?: return@on
    if (data.jsonObject["on"]?.jsonPrimitive?.boolean == true) writers[id] = nameOf(id) else writers.remove(id)
    typing.names = writers.values.toList()
    typing.visibility = if (writers.isEmpty()) View.GONE else View.VISIBLE   // with nobody given it says "typing" alone
}
```

- **`OnlineList(context, count, rows, title, empty, onClose)`:** a close button only with `onClose`. `count` says how many are listed, and the rows do not set it, as a room may count more than it lists. It takes the size you give it, and its rows scroll.
- **`PersonRow(context, avatar, name, sub, actions)`:** `avatar` an `Avatar` or a view of yours, `actions` a `RowAction` each or views of yours.
- **`RowAction(context, icon, label) { … }`:** `icon` a path in a 24 grid, as the Lucide icons are (`RowAction.icons` has ours by name), and `label` what TalkBack says.
- **`Avatar(context, name, picture, sizeDp, shape, presence, showOffline, dotSizeDp, presenceColor, presenceRing, letters, decorative)`:** the picture, or the first letters of the name with none and where it does not load. The dot shows while the person is not offline, and for offline too with `showOffline`. `decorative` where the name is beside it, so TalkBack does not read it twice.
- **`ActivityIndicator(context, entries, activity, names, compact)`:** `names` all doing `activity`, or `entries` for each their own, `TYPING`, `RECORDING` or `UPLOADING`, as `ActivityIndicator.Activity`, since `Activity` alone is Android's. A person typing stops being said when they send `{ "on": false }`, and it is yours to drop someone who went quiet: the web drops them after 4 seconds.
- **Their words and colours** are those of the call screen, `CallScreen.configure`: `online`, `online_empty`, `close`, `presence_online`, `presence_busy`, `presence_away`, `presence_offline`, and `typing`, `typing_named`, `recording`, `recording_named`, `uploading`, `uploading_named`, where `%s` is who. The tokens are the names of the web, `--wplus-color-online`, `--wplus-color-busy`, `--wplus-color-away`, `--wplus-color-offline`, `--wplus-color-text-primary`, `--wplus-color-text-tertiary`, `--wplus-color-border`, `--wplus-color-bg-hover`, `--wplus-color-surface`, `--wplus-color-accent` and `--wplus-color-accent-hover`. With none given they take the values of the web, light or dark with the phone.
- **Right to left** needs `android:supportsRtl="true"` in your manifest, as any view does.
- **Not here:** the web's `overlay` on `OnlineList`, which docks it beside a chat, as a view here goes where you put it, and `AvatarStack`, `GroupAvatar`, `PresenceLabel` and `ReadBy`, which a chat draws.

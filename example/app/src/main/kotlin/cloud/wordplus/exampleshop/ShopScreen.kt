package cloud.wordplus.exampleshop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import cloud.wordplus.realtime.calls.CallState
import cloud.wordplus.realtime.calls.GroupCallState
import cloud.wordplus.realtime.ui.CallScreen
import cloud.wordplus.realtime.ui.LiveStreamScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import android.app.Activity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The shop colours, as its web pages have them. */
object Palette {
    val page = Color(0xFFF8FAFC)
    val card = Color.White
    val line = Color(0xFFE2E8F0)
    val ink = Color(0xFF0F172A)
    val muted = Color(0xFF64748B)
    val blue = Color(0xFF2563EB)
    val green = Color(0xFF13865B)
    val online = Color(0xFF22C55E)
    val bubble = Color(0xFFF1F5F9)
    val stepRing = Color(0xFFCBD5E1)
    val people = mapOf("Ann" to Color(0xFF2563C9), "Ben" to Color(0xFF13865B), "Cara" to Color(0xFFB4561C))
}

@Composable
fun ShopApp(shop: Shop) {
    Box(Modifier.fillMaxSize().background(Palette.page).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Bar(shop)
            if (shop.me == null) SignIn(shop) else ShopScreen(shop)
        }
        Notices(shop, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        // The call itself is the Better Messages call screen (cloud.wordplus:realtime-ui), in an activity of its own. This
        // bar is what the shop shows of it while that screen is put away.
        CallBar(shop, Modifier.align(Alignment.BottomCenter))
    }
}

/** The call going on, while its screen is put away: who and for how long, someone to ring into it, and the way back. */
@Composable
private fun CallBar(shop: Shop, modifier: Modifier) {
    val call = shop.call
    val group = shop.group
    if (call == null && group == null) return
    val title = if (group != null) shop.groupTitle else (call?.peerInfo as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content } ?: call?.peer?.let { Person.named(it)?.name } ?: "Someone"
    Column(
        modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.ink).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (shop.sharingScreen) Text("Sharing your screen", Modifier.testTag("sharing"), color = Color.White, fontSize = 13.sp)
                if (group != null) {
                    val count = shop.groupPeople.size
                    val people = when (shop.groupState) {
                        GroupCallState.CONNECTED -> when (count) {
                            0 -> "Nobody else yet"
                            1 -> "1 other person"
                            else -> "$count other people"
                        }
                        GroupCallState.RECONNECTING -> "Reconnecting…"
                        else -> "Joining…"
                    }
                    Text(people, Modifier.testTag("group-people").semantics { stateDescription = count.toString() }, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                } else {
                    val state = when (shop.callState) {
                        CallState.RINGING -> "Ringing…"
                        CallState.CONNECTING -> "Connecting…"
                        CallState.CONNECTED -> "%d:%02d".format(shop.callSeconds / 60, shop.callSeconds % 60)
                        CallState.RECONNECTING -> "Reconnecting…"
                        else -> ""
                    }
                    Text(state, Modifier.testTag("call-state").semantics { stateDescription = shop.callState?.wire ?: "" }, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                }
            }
            Text(
                "Back to the call",
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { CallScreen.restore() }
                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag("back-to-call"),
                color = Palette.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // Someone else rung into the call, which goes on as a group call.
        if (call != null && shop.callState == CallState.CONNECTED) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (person in Person.ALL.filter { it.id != shop.me?.id && it.id != call.peer }) {
                    Text(
                        "Add ${person.name}",
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).clickable { shop.addToCall(person.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp).testTag("add-${person.id}"),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * The shop live show: Ann presents it, and the app watches it on the screen drawn ready (LiveStreamScreen of
 * cloud.wordplus:realtime-ui), over the shop: its picture, Live and how many watch, its chat and a heart.
 */
@Composable
private fun ShowCard(shop: Shop) {
    val activity = LocalContext.current as? Activity
    val show = shop.show
    // The screen while the app is in the show; closing it leaves the show.
    DisposableEffect(show) {
        val dialog = if (show != null && activity != null) LiveStreamScreen.open(activity, show, "The new mugs", onClose = shop::leaveShow) else null
        onDispose { dialog?.dismiss() }
    }
    Card {
        Heading("Live show")
        Muted(shop.showFailure?.let { "The show is not available here ($it)." } ?: "Ann presents the mugs live.")
        Button(
            onClick = shop::watchShow,
            modifier = Modifier.fillMaxWidth().testTag("watch-show"),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.blue),
        ) { Text("Watch the show") }
    }
}

/** The daily meeting: a group call everyone in the shop may join. */
@Composable
private fun MeetingCard(shop: Shop) {
    Card {
        Heading("Daily meeting")
        Muted("Ann, Ben and Cara, with video.")
        Button(
            onClick = shop::joinMeeting,
            modifier = Modifier.fillMaxWidth().testTag("join-meeting"),
            enabled = shop.group == null && shop.call == null,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.blue),
        ) { Text("Join the meeting") }
    }
}


@Composable
private fun Bar(shop: Shop) {
    Row(
        Modifier.fillMaxWidth().background(Palette.card).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("API demo", color = Palette.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        shop.me?.let { me ->
            Avatar(me.name, 24.dp, online = shop.connected)
            Text(me.name, color = Palette.ink, fontSize = 14.sp)
            TextButton(onClick = shop::signOut) { Text("Sign out", color = Palette.muted, fontSize = 14.sp) }
        }
    }
    HorizontalDivider(color = Palette.line)
}

/** The picture of a person: their initial on their colour, with a dot while they are online. */
@Composable
fun Avatar(name: String, size: Dp = 32.dp, online: Boolean = false) {
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Palette.people[name] ?: Palette.muted), contentAlignment = Alignment.Center) {
            Text(name.take(1), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold)
        }
        if (online) {
            Box(Modifier.align(Alignment.BottomEnd).size(size * 0.34f).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(Palette.online))
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Palette.card).border(1.dp, Palette.line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Heading(text: String) = Text(text, color = Palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, modifier, color = Palette.muted, fontSize = 14.sp)

@Composable
private fun SignIn(shop: Shop) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card {
            Heading("Sign in")
            Muted("As Ann, Ben or Cara. Sign in as someone else in a browser, or on another device.")
            for (person in Person.ALL) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { shop.signIn(person.id) }.padding(vertical = 8.dp).testTag("sign-in-${person.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(person.name)
                    Text(person.name, color = Palette.ink, fontSize = 15.sp)
                    Muted(person.role)
                    Spacer(Modifier.weight(1f))
                    if (shop.signingIn == person.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            shop.failure?.let { Text("Could not connect: $it", color = Color(0xFFB91C1C), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun ShopScreen(shop: Shop) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OrderCard(shop)
        PresenceCard(shop)
        if (shop.showRoom != null) ShowCard(shop)
        MeetingCard(shop)
        if (shop.inChat) {
            ChatCard(shop)
        } else {
            Card {
                Heading("Chat")
                Muted("The chat is between Ann and Ben.")
            }
        }
    }
}

/** The order: its customer watches it move on. The staff move it. */
@Composable
private fun OrderCard(shop: Shop) {
    val reached = shop.reached
    val staff = shop.me?.staff ?: false
    Card {
        Heading(if (staff) "Order #${shop.orderId} · Ben" else "Your order #${shop.orderId}")
        Muted(shop.item)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Shop.STEPS.forEachIndexed { i, step ->
                Column(
                    Modifier.weight(1f).testTag("step-$step").semantics(mergeDescendants = true) {
                        stateDescription = if (i < reached) "done" else if (i == reached) "now" else "next"
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) Color.Transparent else if (i <= reached) Palette.green else Palette.line))
                        val fill = if (i < reached) Palette.green else if (i == reached) Palette.blue else Palette.card
                        val ring = if (i < reached) Palette.green else if (i == reached) Palette.blue else Palette.stepRing
                        Box(Modifier.size(14.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape))
                        Box(Modifier.weight(1f).height(2.dp).background(if (i == Shop.STEPS.lastIndex) Color.Transparent else if (i < reached) Palette.green else Palette.line))
                    }
                    Text(
                        Shop.LABELS[step] ?: step,
                        color = if (i <= reached) Palette.ink else Palette.muted,
                        fontSize = 13.sp,
                        fontWeight = if (i == reached) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (staff) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("Mark as")
                for (next in Shop.STEPS.drop(1)) {
                    OutlinedButton(
                        onClick = { shop.mark(next) },
                        enabled = Shop.STEPS.indexOf(next) > reached,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Palette.line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.blue, disabledContentColor = Palette.muted.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(Shop.LABELS[next] ?: next, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        } else {
            Text(
                if (shop.live) "● Live" else "Connecting…",
                Modifier.testTag("order-live"),
                color = if (shop.live) Palette.green else Palette.muted,
                fontSize = 14.sp,
            )
        }
    }
}

/** Everyone in the shop now. Stepping out leaves the room. */
@Composable
private fun PresenceCard(shop: Shop) {
    Card {
        Heading("In the shop now")
        if (!shop.inShop) Muted("You stepped out.")
        for (member in shop.members) {
            val online = member.id in shop.online
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.weight(1f).testTag("member-${member.id}").semantics(mergeDescendants = true) { stateDescription = if (online) "online" else "offline" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(member.name, online = online)
                    Text(
                        buildAnnotatedString {
                            append(member.name)
                            if (member.id == shop.me?.id) withStyle(SpanStyle(color = Palette.muted)) { append(" (you)") }
                        },
                        color = Palette.ink,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Muted(member.role)
                }
                if (member.id != shop.me?.id) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Palette.blue.copy(alpha = 0.1f))
                            .clickable(enabled = shop.call == null, onClickLabel = "Call ${member.name}") { shop.startCall(member.id) }
                            .testTag("call-${member.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Call ${member.name}", tint = Palette.blue, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        shop.callEnded?.let { reason ->
            Text("Call ended", Modifier.testTag("call-ended").semantics { stateDescription = reason }, color = Palette.muted, fontSize = 13.sp)
        }
        Text(
            if (shop.inShop) "Step out" else "Come back",
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = shop::toggleShop).padding(vertical = 4.dp),
            color = Palette.muted,
            fontSize = 14.sp,
        )
    }
}

/** The chat of Ann and Ben. */
@Composable
private fun ChatCard(shop: Shop) {
    var draft by remember { mutableStateOf("") }
    val send = {
        shop.send(draft)
        draft = ""
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(shop.other?.name ?: "", 30.dp, online = shop.other?.id in shop.online)
            Heading(shop.otherName)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shop.moreMessages) {
                TextButton(onClick = shop::showEarlier) { Text("Earlier messages", color = Palette.muted, fontSize = 14.sp) }
            }
            for (message in shop.messages) {
                val mine = message.from == shop.me?.id
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Text(
                        message.text,
                        Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Palette.blue else Palette.bubble).padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (mine) Color.White else Palette.ink,
                        fontSize = 15.sp,
                    )
                }
            }
        }
        Muted(if (shop.typing) "${shop.otherName} is typing…" else " ", Modifier.testTag("typing"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    shop.typed(it)
                },
                modifier = Modifier.weight(1f).testTag("message-field"),
                placeholder = { Text("Write a message") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Button(onClick = send, Modifier.testTag("send"), colors = ButtonDefaults.buttonColors(containerColor = Palette.blue)) { Text("Send") }
        }
    }
}

/** Notices from the shop server, for a few seconds each. */
@Composable
private fun Notices(shop: Shop, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (notice in shop.notices) {
            Text(
                notice.text,
                Modifier.clip(RoundedCornerShape(50)).background(Palette.ink).padding(horizontal = 14.dp, vertical = 10.dp).testTag("notice"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

package cloud.wordplus.exampleshop

import cloud.wordplus.realtime.calls.SystemCalls
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** The app's pushes from Firebase: a call's go to SystemCalls, which rings the phone. The API demo sends no others. */
class ShopMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = SystemCalls.pushToken(token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (SystemCalls.push(this, message.data)) return
        // An app's own pushes go on here.
    }
}

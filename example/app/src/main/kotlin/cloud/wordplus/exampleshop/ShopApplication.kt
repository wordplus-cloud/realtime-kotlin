package cloud.wordplus.exampleshop

import android.app.Application
import cloud.wordplus.realtime.calls.SystemCalls
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * The app's one shop, and calls as the phone knows them (SystemCalls): started here, as a call's push may start the
 * process with no screen. The pushes come to ShopMessagingService once the app has Firebase (app/google-services.json
 * of your own project). Without it a call rings the app over its line while it runs, and the proof hands the push in.
 */
class ShopApplication : Application() {
    val shop by lazy { Shop(this) }

    override fun onCreate() {
        super.onCreate()
        // The connection of the person signed in, for a call that rings the app closed: null when nobody is.
        SystemCalls.start(this, SystemCalls.Options(name = "API demo", screen = MainActivity::class.java)) { shop.connection() }
        // The token as it stands, besides each new one ShopMessagingService gets.
        if (FirebaseApp.getApps(this).isNotEmpty()) FirebaseMessaging.getInstance().token.addOnSuccessListener { SystemCalls.pushToken(it) }
    }
}

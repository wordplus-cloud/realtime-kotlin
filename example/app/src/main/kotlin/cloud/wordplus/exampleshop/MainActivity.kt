// The API demo as an Android app: sign in as Ann (support), Ben (a customer) or Cara (the warehouse), then watch
// The order of Ben move on, see who is in the shop and who is online, and chat with typing. The same rooms as the shop web
// pages, so the app and a browser signed in as someone else see each other.
package cloud.wordplus.exampleshop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val shop: Shop get() = (application as ShopApplication).shop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The shop address and a fresh start, as the instrumented test opens it. Otherwise the Express example on the Mac.
        shop.open(intent.getStringExtra("shop"), intent.getBooleanExtra("reset", false))
        setContent { ShopApp(shop) }

        // The microphone and camera for calls, and on Android 13 and later the notifications a call rings with, asked for
        // once. A call without the first two connects and sends nothing.
        val wanted = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        // Left for good, the app is closed: the connection goes, and a call rings the app through its push again.
        if (isFinishing) shop.closeConnection()
        super.onDestroy()
    }
}

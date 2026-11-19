package cloud.wordplus.realtime.calls

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the rooms library reads to keep its nodes in the app's cache folder (Connection.kt, defaultNodeStore, tested on the
 * JVM by NodeStoreTest), as a real Android has them: it is plain Kotlin, with no Context to ask.
 */
@RunWith(AndroidJUnit4::class)
class NodeStoreDeviceTest {
    @Test
    fun androidNamesItselfAndTheCacheFolder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Android Runtime", System.getProperty("java.runtime.name"))
        assertEquals(context.cacheDir.path, System.getProperty("java.io.tmpdir"))
    }
}

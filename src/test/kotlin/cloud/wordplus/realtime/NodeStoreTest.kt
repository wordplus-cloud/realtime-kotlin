package cloud.wordplus.realtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The nodes kept between launches when the app doesn't say where, as Swift's are in UserDefaults
 * (the node cache, kept on disk): a file of an Android app's cache folder, memory
 * anywhere else. The calls module's NodeStoreDeviceTest checks the two properties this reads on a real Android.
 */
class NodeStoreTest {
    private val server = "https://ws.wordplus.cloud"
    private val nodes = NodeCache(nodes = listOf(CachedNode("https://ws-a.de.wordplus.cloud", System.currentTimeMillis() / 1000.0 + 3600)))

    @Test
    fun onAndroidTheNodesOutliveTheStoreThatKeptThem() {
        val folder = Files.createTempDirectory("nodes").toFile()
        defaultNodeStore("Android Runtime", folder.path).save(server, nodes)

        // The next launch.
        assertEquals(nodes.nodes, defaultNodeStore("Android Runtime", folder.path).load(server).nodes)
        // No secret and no address in a file's name, and nothing half written left behind.
        val files = File(folder, "wordplus-realtime-nodes").listFiles()!!.map { it.name }
        assertEquals(1, files.size)
        assertTrue(files[0].matches(Regex("[0-9a-f]{64}")), files[0])
    }

    @Test
    fun eachServerHasItsOwn() {
        val folder = Files.createTempDirectory("nodes").toFile()
        val store = defaultNodeStore("Android Runtime", folder.path)
        store.save(server, nodes)
        assertTrue(store.load("https://other.example").nodes.isEmpty())
    }

    @Test
    fun aFolderItCannotWriteToStillKeepsThemForThisLaunch() {
        val file = Files.createTempFile("not-a-folder", "").toFile()
        val store = defaultNodeStore("Android Runtime", file.path)
        store.save(server, nodes)
        assertEquals(nodes.nodes, store.load(server).nodes)
    }

    @Test
    fun aDamagedFileIsNoNodes() {
        val folder = Files.createTempDirectory("nodes").toFile()
        defaultNodeStore("Android Runtime", folder.path).save(server, nodes)
        File(folder, "wordplus-realtime-nodes").listFiles()!!.single().writeText("not json")
        assertTrue(defaultNodeStore("Android Runtime", folder.path).load(server).nodes.isEmpty())
    }

    @Test
    fun anywhereElseOnlyInMemory() {
        assertIs<MemoryNodeStore>(defaultNodeStore("OpenJDK Runtime Environment", Files.createTempDirectory("nodes").toString()))
        assertIs<MemoryNodeStore>(defaultNodeStore(null, null))
        assertIs<MemoryNodeStore>(defaultNodeStore())
    }
}

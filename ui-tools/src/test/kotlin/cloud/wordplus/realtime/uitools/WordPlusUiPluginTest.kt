package cloud.wordplus.realtime.uitools

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * cloud.wordplus.realtime-ui in a project made for each test: wordplusUiAdd copies the call screen with its packages and
 * records it, wordplusUiDiff shows what changed in ours since, wordplusUiUpdate takes ours only where the app never
 * changed a file, and wordplusUiAccept records a merge.
 */
class WordPlusUiPluginTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun clean() = dirs.forEach { it.deleteRecursively() }

    private fun project(): File = Files.createTempDirectory("wordplus-ui-").toFile().also { dir ->
        dirs += dir
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"shop\"\n")
        dir.resolve("build.gradle.kts").writeText("plugins { id(\"cloud.wordplus.realtime-ui\") }\n")
    }

    private fun run(dir: File, vararg tasks: String): String =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*tasks).build().output

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `copies the screen with its packages, records each file, and keeps yours when added again`() {
        val dir = project()
        val said = run(dir, "wordplusUiAdd")

        assertContains(said, "The call screen: copied into src/main/kotlin/")
        val stage = dir.resolve("src/main/kotlin/cloud/wordplus/realtime/ui/calls/CallsStage.kt")
        val screen = dir.resolve("src/main/kotlin/cloud/wordplus/realtime/ui/CallScreen.kt")
        assertTrue(stage.isFile)
        assertTrue(screen.readText().startsWith("// call-screen: copied from cloud.wordplus:realtime-ui"))
        assertContains(screen.readText(), "package cloud.wordplus.realtime.ui")

        val record = Json.parse(dir.resolve("wordplus-ui.json").readText()) as Map<*, *>
        val files = ((record["components"] as Map<*, *>)["call-screen"] as Map<*, *>)["files"] as Map<*, *>
        assertTrue(files.containsKey("cloud/wordplus/realtime/ui/calls/CallsStage.kt"))
        assertEquals(sha(stage.readText()), (files["cloud/wordplus/realtime/ui/calls/CallsStage.kt"] as Map<*, *>)["sha256"])

        // The preview comes with the screen, and its tests go into the module's instrumented tests, recorded as ours.
        assertTrue(dir.resolve("src/main/kotlin/cloud/wordplus/realtime/ui/CallScreenPreviewActivity.kt").isFile)
        val preview = dir.resolve("src/androidTest/kotlin/cloud/wordplus/realtime/ui/PreviewTest.kt")
        assertContains(preview.readText(), "class PreviewTest")
        assertTrue(dir.resolve("src/androidTest/kotlin/cloud/wordplus/realtime/ui/PiecesTest.kt").isFile)
        assertEquals(sha(preview.readText()), (files["androidTest/cloud/wordplus/realtime/ui/PreviewTest.kt"] as Map<*, *>)["sha256"])
        assertContains(said, "Its tests: in src/androidTest/kotlin")

        stage.appendText("\n// mine\n")
        assertContains(run(dir, "wordplusUiAdd"), "already in src/main/kotlin")
        assertContains(stage.readText(), "// mine")
        assertContains(run(dir, "wordplusUiDiff"), "Nothing changed in ours")
    }

    @Test
    fun `shows what changed in ours, takes ours only into the files you never changed, and records a merge`() {
        val dir = project()
        run(dir, "wordplusUiAdd")
        val timer = "cloud/wordplus/realtime/ui/calls/CallsAvatars.kt"
        val icon = "cloud/wordplus/realtime/ui/calls/CallsIcon.kt"

        // Ours was something else in an older version: the app never changed the timer, and changed the icons.
        val base = (Json.parse(dir.resolve(".wordplus-ui/call-screen.json").readText()) as Map<*, *>).entries.associate { (k, v) -> k as String to v as String }.toMutableMap()
        val record = Json.parse(dir.resolve("wordplus-ui.json").readText()) as Map<*, *>
        val files = (((record["components"] as Map<*, *>)["call-screen"] as Map<*, *>)["files"] as Map<*, *>).entries
            .associate { (k, v) -> k as String to (v as Map<*, *>).entries.associate { (a, b) -> a as String to b as String }.toMutableMap() }.toMutableMap()
        val timerNow = base.getValue(timer)
        val iconNow = base.getValue(icon)
        val timerThen = timerNow.replaceFirst("object CallsAvatars", "object CallsAvatars /* older */")
        val iconThen = iconNow.replaceFirst("object CallsIcons", "object CallsIcons /* older */")
        base[timer] = timerThen
        base[icon] = iconThen
        files.getValue(timer).apply { put("version", "0.9.0"); put("sha256", sha(timerThen)) }
        files.getValue(icon).apply { put("version", "0.9.0"); put("sha256", sha(iconThen)) }
        dir.resolve(".wordplus-ui/call-screen.json").writeText(Json.write(base))
        dir.resolve("wordplus-ui.json").writeText(Json.write(mapOf("dir" to "src/main/kotlin", "components" to mapOf("call-screen" to mapOf("files" to files)))))
        dir.resolve("src/main/kotlin/$timer").writeText(timerThen)
        dir.resolve("src/main/kotlin/$icon").writeText("$iconThen\n// my icons\n")

        val diff = run(dir, "wordplusUiDiff")
        assertContains(diff, "=== $timer: ours changed since 0.9.0 (now 1.0.0); yours is as copied: wordplusUiUpdate takes ours")
        assertContains(diff, "=== $icon: ours changed since 0.9.0 (now 1.0.0); yours changed too: merge by hand")
        assertContains(diff, "-object CallsIcons /* older */")
        assertTrue(Regex("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@").containsMatchIn(diff))

        val updated = run(dir, "wordplusUiUpdate")
        assertContains(updated, "$timer: you never changed it: now ours of 1.0.0")
        assertContains(updated, "$icon: yours changed, and so did ours")
        assertEquals(timerNow, dir.resolve("src/main/kotlin/$timer").readText())
        assertContains(dir.resolve("src/main/kotlin/$icon").readText(), "// my icons")

        dir.resolve("src/main/kotlin/$icon").writeText("$iconNow\n// my icons\n")
        assertContains(run(dir, "wordplusUiAccept"), "The call screen: yours has ours of 1.0.0")
        assertContains(run(dir, "wordplusUiDiff"), "Nothing changed in ours")
        assertFalse(dir.resolve("src/main/kotlin/$icon").readText().contains("older"))
    }
}

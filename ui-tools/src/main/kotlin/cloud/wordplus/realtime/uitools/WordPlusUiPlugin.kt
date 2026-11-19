package cloud.wordplus.realtime.uitools

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.security.MessageDigest

/**
 * `cloud.wordplus.realtime-ui`: the call screen copied into the app module it is applied to, to change as far as it
 * needs.
 *
 * ```
 * ./gradlew :app:wordplusUiAdd       copies the screen into src/main/kotlin (its own packages)
 * ./gradlew :app:wordplusUiDiff      what changed in ours since the copy, file by file
 * ./gradlew :app:wordplusUiUpdate    takes ours into every file the app never changed; the rest is left to merge
 * ./gradlew :app:wordplusUiAccept    records that the app's copy now has ours, after merging what diff showed
 * ```
 *
 * Only the screen is copied: the calls under it stay in cloud.wordplus:realtime-calls, so their fixes still reach the
 * app. wordplus-ui.json in the module records each copied file's version and the hash of ours as it was copied, and
 * .wordplus-ui/call-screen.json keeps ours as it was, which diff compares with ours now.
 */
class WordPlusUiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("wordplusUiAdd", AddTask::class.java) { it.group = GROUP; it.description = "Copies the call screen's code into this module, to change as far as you need." }
        project.tasks.register("wordplusUiDiff", DiffTask::class.java) { it.group = GROUP; it.description = "Shows what changed in ours since you copied the call screen." }
        project.tasks.register("wordplusUiUpdate", UpdateTask::class.java) { it.group = GROUP; it.description = "Takes ours into the call screen's files you never changed." }
        project.tasks.register("wordplusUiAccept", AcceptTask::class.java) { it.group = GROUP; it.description = "Records that your call screen now has ours." }
    }

    companion object {
        const val GROUP = "wordplus ui"
    }
}

/** What every task works on: ours as this plugin carries it, the app's copy, and their records. */
abstract class CopyTask : DefaultTask() {
    @get:Internal
    protected val copies: Copies get() = Copies(project.projectDir, project.projectDir.resolve(dir), logger::lifecycle)

    /** Where the copy's files go, inside the module: their packages' folders under it. */
    @get:Input
    @set:Option(option = "dir", description = "The folder the copy goes in, in the module (src/main/kotlin by default).")
    var dir: String = "src/main/kotlin"
}

abstract class AddTask : CopyTask() {
    @get:Input
    @set:Option(option = "force", description = "Copies the screen again over yours.")
    var force: Boolean = false

    @TaskAction
    fun add() = copies.add(force)
}

abstract class DiffTask : CopyTask() {
    @TaskAction
    fun diff() = copies.diff()
}

abstract class UpdateTask : CopyTask() {
    @TaskAction
    fun update() = copies.update()
}

abstract class AcceptTask : CopyTask() {
    @TaskAction
    fun accept() = copies.accept()
}

/** Ours' instrumented tests, by their path in the plugin. */
private const val TESTS = "androidTest/"

/** The app's copy of the call screen, against ours as this plugin carries it. */
class Copies(private val module: File, private val into: File, private val say: (String) -> Unit) {
    private val component = "call-screen"
    private val recordFile = module.resolve("wordplus-ui.json")
    private val baseFile = module.resolve(".wordplus-ui/$component.json")

    private val version: String = resource("version.txt").trim()

    /** Ours: each file by its path under the source root, with a line on top saying where it came from. */
    private val ours: Map<String, String> by lazy {
        resource("files.txt").lines().filter { it.isNotBlank() }.associateWith { path ->
            "// call-screen: copied from cloud.wordplus:realtime-ui ($path) by ./gradlew wordplusUiAdd. It is yours now:\n" +
                "// change anything. ./gradlew wordplusUiDiff shows what changed in ours since.\n" + resource(path)
        }
    }

    private fun resource(path: String): String =
        Copies::class.java.getResourceAsStream("/wordplus-ui/$component/$path")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("The plugin carries no $path: rebuild it.")

    /** Where a file of ours goes: the screen into the copy's folder, its tests (androidTest/…) into the module's. */
    private fun fileOf(path: String): File =
        if (path.startsWith(TESTS)) module.resolve("src/androidTest/kotlin").resolve(path.removePrefix(TESTS)) else into.resolve(path)

    fun add(force: Boolean) {
        val record = readRecord()
        if (record.containsKey(component) && !force) {
            say("The call screen is already in ${into.relativeTo(module)}. ./gradlew wordplusUiDiff shows what changed in ours since; --force copies it again over yours.")
            return
        }
        val base = linkedMapOf<String, String>()
        val files = linkedMapOf<String, Pair<String, String>>()
        for ((path, text) in ours) {
            fileOf(path).apply { parentFile.mkdirs() }.writeText(text)
            base[path] = text
            files[path] = version to sha(text)
        }
        record[component] = files
        writeRecord(record)
        writeBase(base)
        say(
            """
            The call screen: copied into ${into.relativeTo(module)}/ (${files.count { !it.key.startsWith(TESTS) }} files, their packages as ours). It is yours now: change anything.
            The calls under it stay in cloud.wordplus:realtime-calls, so their fixes still reach you.
            Its tests: in src/androidTest/kotlin, on the preview's states with no call. Run them after a change with
            ./gradlew connectedDebugAndroidTest (the module needs testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            and androidTestImplementation androidx.test.ext:junit, androidx.test:runner and androidx.test:core).

            In the module: take cloud.wordplus:realtime-ui out of its dependencies (your copy is the screen now), keep
            cloud.wordplus:realtime-calls, add cloud.wordplus:rtc-client-android:1.0.3 (the calls' room client) and
            androidx.core:core-ktx, and declare the
            screen's activities in your AndroidManifest.xml, as the library did:
                <activity android:name="cloud.wordplus.realtime.ui.calls.CallsActivity" android:exported="false" android:launchMode="singleTop"
                    android:excludeFromRecents="true" android:showOnLockScreen="true" android:turnScreenOn="true" android:supportsPictureInPicture="true"
                    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"
                    android:theme="@android:style/Theme.NoTitleBar.Fullscreen" />
                <activity android:name="cloud.wordplus.realtime.ui.CallRingActivity" android:exported="false" android:excludeFromRecents="true"
                    android:showOnLockScreen="true" android:turnScreenOn="true" android:theme="@android:style/Theme.NoTitleBar.Fullscreen" />
                <activity android:name="cloud.wordplus.realtime.ui.CallScreenPreviewActivity" android:exported="false"
                    android:theme="@android:style/Theme.Material.Light" />
            Then use it as before: CallScreen.attach(context, calls). Every state of your copy, with no call: CallScreen.preview(context).
            When the library updates: ./gradlew wordplusUiDiff.
            """.trimIndent()
        )
    }

    fun diff() {
        val record = readRecord()
        val files = record[component] ?: error("No copy here yet: ./gradlew wordplusUiAdd.")
        var changed = 0
        for (state in states(files, readBase())) {
            if (!state.oursChanged) continue
            changed++
            val from = files[state.path]?.first ?: "none"
            say("\n=== ${state.path}: ours changed since $from (now $version)" + if (state.yoursChanged) "; yours changed too: merge by hand" else "; yours is as copied: wordplusUiUpdate takes ours")
            say(unified(state.then ?: "", state.now ?: "", "ours@$from/${state.path}", "ours@$version/${state.path}"))
        }
        say(if (changed == 0) "Nothing changed in ours since your copy ($version)." else "\n$changed file(s) changed in ours. Merge each into yours, build and try a call, then ./gradlew wordplusUiAccept.")
    }

    fun update() {
        val record = readRecord()
        val files = record[component] ?: error("No copy here yet: ./gradlew wordplusUiAdd.")
        val base = readBase()
        for (state in states(files, base)) {
            if (!state.oursChanged) continue
            if (state.yoursChanged) {
                say("${state.path}: yours changed, and so did ours: ./gradlew wordplusUiDiff shows ours to merge.")
                continue
            }
            val now = state.now
            if (now == null) {
                fileOf(state.path).delete()
                base.remove(state.path)
                files.remove(state.path)
                say("${state.path}: gone from ours, and you never changed it: removed.")
                continue
            }
            fileOf(state.path).apply { parentFile.mkdirs() }.writeText(now)
            base[state.path] = now
            files[state.path] = version to sha(now)
            say("${state.path}: you never changed it: now ours of $version.")
        }
        writeRecord(record)
        writeBase(base)
    }

    fun accept() {
        val record = readRecord()
        val files = record[component] ?: error("No copy here yet: ./gradlew wordplusUiAdd.")
        val base = readBase()
        for (state in states(files, base)) {
            val now = state.now
            if (now == null) {
                base.remove(state.path)
                files.remove(state.path)
                continue
            }
            base[state.path] = now
            files[state.path] = version to sha(now)
            if (state.yours == null) {
                fileOf(state.path).apply { parentFile.mkdirs() }.writeText(now)
                say("${state.path}: new in ours, copied.")
            }
        }
        writeRecord(record)
        writeBase(base)
        say("The call screen: yours has ours of $version.")
    }

    private class State(val path: String, val then: String?, val now: String?, val yours: String?, val oursChanged: Boolean, val yoursChanged: Boolean)

    private fun states(files: Map<String, Pair<String, String>>, base: Map<String, String>): List<State> =
        (files.keys + ours.keys).toSortedSet().map { path ->
            val yours = fileOf(path).takeIf { it.isFile }?.readText()
            State(
                path, base[path], ours[path], yours,
                oursChanged = base[path] != ours[path],
                yoursChanged = yours != null && sha(yours) != files[path]?.second,
            )
        }

    // The records: plain JSON, written and read here without a library.

    private fun readRecord(): MutableMap<String, MutableMap<String, Pair<String, String>>> {
        if (!recordFile.isFile) return linkedMapOf()
        val json = Json.parse(recordFile.readText()) as Map<*, *>
        val components = json["components"] as? Map<*, *> ?: return linkedMapOf()
        return components.entries.associateTo(linkedMapOf()) { (name, value) ->
            val files = (value as Map<*, *>)["files"] as Map<*, *>
            name as String to files.entries.associateTo(linkedMapOf()) { (path, file) ->
                val f = file as Map<*, *>
                path as String to (f["version"] as String to f["sha256"] as String)
            }
        }
    }

    private fun writeRecord(record: Map<String, Map<String, Pair<String, String>>>) {
        recordFile.writeText(Json.write(mapOf(
            "dir" to into.relativeTo(module).invariantSeparatorsPath,
            "components" to record.mapValues { (_, files) -> mapOf("files" to files.mapValues { (_, f) -> mapOf("version" to f.first, "sha256" to f.second) }) },
        )) + "\n")
    }

    private fun readBase(): MutableMap<String, String> =
        if (baseFile.isFile) (Json.parse(baseFile.readText()) as Map<*, *>).entries.associateTo(linkedMapOf()) { (k, v) -> k as String to v as String } else linkedMapOf()

    private fun writeBase(base: Map<String, String>) {
        baseFile.parentFile.mkdirs()
        baseFile.writeText(Json.write(base) + "\n")
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

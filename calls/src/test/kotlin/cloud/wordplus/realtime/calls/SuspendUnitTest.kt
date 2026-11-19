package cloud.wordplus.realtime.calls

import cloud.wordplus.realtime.Realtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.util.jar.JarFile

/**
 * Whatever a suspending function of the SDK declared as returning nothing resumes its caller with Unit. Kotlin compiles
 * one whose body ends in a suspending call with nothing after it to no state of its own: it hands its caller's
 * continuation to that call, so what that call resumes with reaches the caller as it is. An app on Kotlin 2.4 casts it to
 * Unit in some places, and `stream.send()` threw `ClassCastException: JSONObject cannot be cast to kotlin.Unit`. So
 * a function that returns nothing never ends that way in a call that returns something. Read from the compiled classes,
 * as no source says it, and whichever Kotlin built them.
 */
class SuspendUnitTest {
    /** A suspending function by its class, name and descriptor. */
    private data class Name(val owner: String, val name: String, val desc: String)

    private val loader = javaClass.classLoader!!
    private val read = HashMap<String, ClassNode?>()

    private fun node(owner: String): ClassNode? = read.getOrPut(owner) {
        loader.getResourceAsStream("$owner.class")?.use { stream ->
            ClassNode().also { ClassReader(stream).accept(it, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
        }
    }

    /** The class files a compiled class came with, in its folder or its jar. */
    private fun classesBeside(type: Class<*>): List<String> {
        val where = File(type.protectionDomain!!.codeSource.location.toURI())
        return if (where.isDirectory) {
            where.walkTopDown().filter { it.extension == "class" }.map { it.relativeTo(where).path.removeSuffix(".class") }.toList()
        } else {
            JarFile(where).use { jar -> jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }.toList() }
        }
    }

    /** The method a call means, looked up through the classes it comes from. */
    private fun resolve(owner: String, name: String, desc: String): Pair<ClassNode, MethodNode>? {
        val type = node(owner) ?: return null
        type.methods.firstOrNull { it.name == name && it.desc == desc }?.let { return type to it }
        return (listOfNotNull(type.superName) + type.interfaces).firstNotNullOfOrNull { resolve(it, name, desc) }
    }

    private val MethodNode.suspends get() = desc.endsWith("Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")

    private val MethodNode.returnsNothing get() = suspends && signature?.endsWith("Lkotlin/coroutines/Continuation<-Lkotlin/Unit;>;)Ljava/lang/Object;") == true

    /** Whether it keeps a state of its own between suspending: Kotlin's continuation for it, which it looks for first. */
    private fun MethodNode.hasOwnState(owner: String) = instructions.any { it is TypeInsnNode && it.opcode == Opcodes.INSTANCEOF && it.desc.startsWith("$owner$") }

    private val leaks = HashMap<Name, String?>()

    /** What this function, declared as returning nothing, lets through to its caller, or null if it can't let anything through. */
    private fun leak(type: ClassNode, method: MethodNode): String? = leaks.getOrPut(Name(type.name, method.name, method.desc)) {
        if (method.access and Opcodes.ACC_ABSTRACT != 0 || method.hasOwnState(type.name)) return@getOrPut null
        method.instructions.asSequence().filterIsInstance<MethodInsnNode>().filter { it.desc.endsWith("Lkotlin/coroutines/Continuation;)Ljava/lang/Object;") }.firstNotNullOfOrNull { call ->
            val (ownerNode, callee) = resolve(call.owner, call.name, call.desc) ?: return@firstNotNullOfOrNull "${call.owner}.${call.name} (not found)"
            when {
                !callee.returnsNothing -> "${call.owner}.${call.name} returns ${callee.signature?.substringAfterLast("Continuation<-")?.substringBefore(">;)") ?: "something"}"
                ownerNode.name.startsWith("cloud/wordplus/") -> leak(ownerNode, callee)
                else -> null
            }
        }
    }

    /** Each public function, as the JVM sees it, of the classes beside this one that lets something through. */
    private fun leaksBeside(type: Class<*>): List<String> = classesBeside(type).flatMap { owner ->
        val node = node(owner) ?: return@flatMap emptyList()
        node.methods.filter { it.access and Opcodes.ACC_PUBLIC != 0 && it.returnsNothing }.mapNotNull { method ->
            leak(node, method)?.let { "${node.name.substringAfterLast('/')}.${method.name}: $it" }
        }
    }

    @Test
    fun nothingACallersContinuationIsHandedToReturnsAValue() {
        assertEquals(emptyList<String>(), leaksBeside(LiveStream::class.java) + leaksBeside(Realtime::class.java))
    }

    @Test
    fun thisLooksAtWhatItMeansToLookAt() {
        // The check is only as good as its reading of the classes: these are functions it must find, and it must see through
        // to a function's own state.
        val stream = node("cloud/wordplus/realtime/calls/LiveStream")!!
        val send = stream.methods.first { it.name == "send" }
        assertTrue(send.returnsNothing)
        assertTrue(classesBeside(LiveStream::class.java).contains("cloud/wordplus/realtime/calls/LiveStream"))
        assertTrue(classesBeside(Realtime::class.java).contains("cloud/wordplus/realtime/Realtime"))
        val hand = stream.methods.first { it.name == "hand" }
        assertTrue("hand() does more after its answer, so it has a state of its own", hand.hasOwnState(stream.name))
    }
}

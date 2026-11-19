package cloud.wordplus.realtime.calls

/** A handler of a call, a group call or a ring that can be taken off again (`off`). */
class CallListener internal constructor()

/** The handlers of one event, in the order they came. On the main thread, where the calls live. */
internal class CallHandlers<T> {
    private val list = ArrayList<Pair<CallListener, (T) -> Unit>>()

    fun add(handler: (T) -> Unit, listener: CallListener = CallListener()): CallListener {
        list.add(listener to handler)
        return listener
    }

    fun remove(listener: CallListener) {
        list.removeAll { it.first === listener }
    }

    /** Each handler as it was when fired: one may take itself or another off. */
    fun fire(value: T) = list.toList().forEach { it.second(value) }
}

internal fun CallHandlers<Unit>.fire() = fire(Unit)

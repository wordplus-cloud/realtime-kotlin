package cloud.wordplus.realtime.calls

/**
 * A call's name for this app's user and another, from a template, as the call buttons of the web take it: `{pair}` the two
 * ids in order, `{me}`, `{id}`. Both people so name the same call, whichever calls. A name with none of them is as it
 * is. @wordplus/realtime-client's sdk-calls/call-name.ts, rule for rule.
 */
internal object CallName {
    /** Throws `no_identity` where it needs this app's user and the app's server named none. */
    fun named(template: String, me: String?, id: String): String {
        if (!template.contains('{')) return template
        if (me == null && (template.contains("{pair}") || template.contains("{me}"))) throw CallException("no_identity")
        val own = me ?: ""
        val pair = pairOf(own, id).joinToString(":")
        return template.replace("{pair}", pair).replace("{me}", own).replace("{id}", id)
    }

    /**
     * The two ids in order, the smaller first: as numbers when both are whole numbers (9 before 10, as an app's server
     * sorts them), else as text, so both people name the same call either way round.
     */
    private fun pairOf(one: String, other: String): List<String> {
        val digits = { id: String -> id.isNotEmpty() && id.all { it in '0'..'9' } }
        val first = if (digits(one) && digits(other)) compareNumbers(one, other) <= 0 else one <= other
        return if (first) listOf(one, other) else listOf(other, one)
    }

    /** Whole numbers of any length compared exactly, without parsing past what a number holds. */
    private fun compareNumbers(one: String, other: String): Int {
        val a = one.trimStart('0').ifEmpty { "0" }
        val b = other.trimStart('0').ifEmpty { "0" }
        return if (a.length != b.length) a.length - b.length else a.compareTo(b)
    }
}

package cloud.wordplus.realtime.uitools

/** The records' JSON, read and written without a library: objects, strings, numbers, booleans and null. */
internal object Json {
    fun parse(text: String): Any? = Reader(text).run { value().also { space(); require(at == text.length) { "JSON ends early at $at" } } }

    fun write(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
            "$indent  ${quote(k as String)}: ${write(v, "$indent  ")}"
        }
        is List<*> -> value.joinToString(", ", "[", "]") { write(it, indent) }
        else -> error("Not JSON: $value")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private class Reader(val s: String) {
        var at = 0

        fun space() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        fun value(): Any? {
            space()
            return when (val c = s[at]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $at")
            }
        }

        fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, at)) { "Expected $w at $at" }
            at += w.length
            return v
        }

        fun number(): Number {
            val start = at
            while (at < s.length && (s[at].isDigit() || s[at] in "-+.eE")) at++
            val n = s.substring(start, at)
            return n.toLongOrNull() ?: n.toDouble()
        }

        fun string(): String = buildString {
            at++
            while (s[at] != '"') {
                val c = s[at++]
                if (c != '\\') { append(c); continue }
                when (val e = s[at++]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'u' -> { append(s.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                    else -> append(e)
                }
            }
            at++
        }

        fun obj(): Map<String, Any?> {
            at++
            val out = linkedMapOf<String, Any?>()
            space()
            if (s[at] == '}') { at++; return out }
            while (true) {
                space()
                val key = string()
                space()
                require(s[at++] == ':') { "Expected : at ${at - 1}" }
                out[key] = value()
                space()
                if (s[at++] == '}') return out
            }
        }

        fun list(): List<Any?> {
            at++
            val out = mutableListOf<Any?>()
            space()
            if (s[at] == ']') { at++; return out }
            while (true) {
                out += value()
                space()
                if (s[at++] == ']') return out
            }
        }
    }
}

/** A unified diff of two texts, three lines of context (Myers' algorithm), as the web's CLI and `diff -u` write it. */
internal fun unified(a: String, b: String, from: String, to: String): String {
    val x = if (a.isEmpty()) emptyList() else a.split('\n')
    val y = if (b.isEmpty()) emptyList() else b.split('\n')
    val ops = edits(x, y)
    val changed = ops.indices.filter { ops[it].first != ' ' }
    if (changed.isEmpty()) return ""
    val context = 3
    val groups = mutableListOf<IntArray>()
    for (i in changed) {
        val last = groups.lastOrNull()
        if (last != null && i - last[1] <= context * 2) last[1] = i else groups += intArrayOf(i, i)
    }
    val lines = mutableListOf("--- $from", "+++ $to")
    for ((first, last) in groups.map { it[0] to it[1] }) {
        val start = maxOf(0, first - context)
        val end = minOf(ops.size, last + context + 1)
        val hunk = ops.subList(start, end)
        val before = ops.subList(0, start)
        val fromStart = before.count { it.first != '+' } + 1
        val toStart = before.count { it.first != '-' } + 1
        lines += "@@ -$fromStart,${hunk.count { it.first != '+' }} +$toStart,${hunk.count { it.first != '-' }} @@"
        hunk.forEach { lines += "${it.first}${it.second}" }
    }
    return lines.joinToString("\n")
}

private fun edits(x: List<String>, y: List<String>): List<Pair<Char, String>> {
    val n = x.size
    val m = y.size
    val max = n + m
    val v = IntArray(2 * max + 2)
    val trace = mutableListOf<IntArray>()
    var done = false
    var d = 0
    while (d <= max && !done) {
        trace += v.copyOf()
        var k = -d
        while (k <= d) {
            var i = if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) v[max + k + 1] else v[max + k - 1] + 1
            var j = i - k
            while (i < n && j < m && x[i] == y[j]) { i++; j++ }
            v[max + k] = i
            if (i >= n && j >= m) { done = true; break }
            k += 2
        }
        d++
    }
    val ops = mutableListOf<Pair<Char, String>>()
    var i = n
    var j = m
    for (step in trace.indices.reversed()) {
        val w = trace[step]
        val k = i - j
        val prevK = if (k == -step || (k != step && w[max + k - 1] < w[max + k + 1])) k + 1 else k - 1
        val prevI = w[max + prevK]
        val prevJ = prevI - prevK
        while (i > prevI && j > prevJ) { i--; j--; ops += ' ' to x[i] }
        if (step > 0) {
            if (i == prevI) { j--; ops += '+' to y[j] } else { i--; ops += '-' to x[i] }
        }
    }
    return ops.reversed()
}

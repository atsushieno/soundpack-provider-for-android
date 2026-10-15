package org.androidaudioplugin.soundpack

/** The files an SFZ instrument names, in the order it names them: `#include`d files, and
 * `sample=` paths joined to `default_path`, as pack-relative paths. It follows sfizz's parser
 * where that decides which file is meant (include and sample paths relative to the entry's
 * folder, `\` as `/`, `#define` taking one word, `$` expanding the shortest defined name, an
 * opcode value ending at whitespace before the next `name=`), so an app can open those files
 * before the engine asks for them. It is not a full SFZ parser: generated samples (`*sine`)
 * and paths that would leave the pack are skipped. */
object SfzReferences {
    private const val MAX_INCLUDE_DEPTH = 32
    private const val MAX_EXPANSION_ROUNDS = 16
    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** [read] returns the text of a pack file, or null when there is none. */
    fun scan(entryPath: String, read: (String) -> String?): List<String> = Scanner(PackPath.parent(entryPath), read).run(entryPath)

    private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private class Scanner(private val root: String, private val read: (String) -> String?) {
        val found = LinkedHashSet<String>()
        val included = HashSet<String>()
        val defines = HashMap<String, String>()
        var defaultPath = ""

        fun run(entryPath: String): List<String> {
            included += entryPath
            read(entryPath)?.let { process(it, 0) }
            return found.toList()
        }

        private fun resolve(relative: String): String? {
            if (relative.isEmpty() || relative.startsWith('/') || ':' in relative)
                return null
            return PackPath.normalize(if (root.isEmpty()) relative else "$root/$relative")
        }

        private fun process(text: String, depth: Int) {
            for (line in text.replace(blockComment, " ").lines())
                parseLine(line.substringBefore("//"), depth)
        }

        private fun parseLine(line: String, depth: Int) {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c.isWhitespace() -> i++
                    c == '<' -> {
                        val end = line.indexOf('>', i)
                        if (end < 0) return
                        if (line.substring(i + 1, end).trim() == "control")
                            defaultPath = ""
                        i = end + 1
                    }
                    c == '#' -> i = directive(line, i + 1, depth) ?: return
                    isIdentifierChar(c) || c == '$' -> i = opcode(line, i) ?: return
                    else -> i++
                }
            }
        }

        /** Returns where parsing continues, or null to skip the rest of the line. */
        private fun directive(line: String, start: Int, depth: Int): Int? {
            var i = start
            while (i < line.length && isIdentifierChar(line[i])) i++
            val name = line.substring(start, i)
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            when (name) {
                "define" -> {
                    if (i >= line.length || line[i] != '$') return null
                    val idStart = ++i
                    while (i < line.length && isIdentifierChar(line[i])) i++
                    val id = line.substring(idStart, i)
                    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
                    val valueStart = i
                    while (i < line.length && line[i] != ' ' && line[i] != '\t') i++
                    if (id.isNotEmpty())
                        defines[id] = line.substring(valueStart, i)
                    return i
                }
                "include" -> {
                    if (i >= line.length || line[i] != '"') return null
                    val end = line.indexOf('"', i + 1)
                    if (end < 0) return null
                    val path = resolve(expand(line.substring(i + 1, end)).replace('\\', '/'))
                    if (path != null && depth < MAX_INCLUDE_DEPTH && included.add(path)) {
                        read(path)?.let { text ->
                            found += path
                            process(text, depth + 1)
                        }
                    }
                    return end + 1
                }
                else -> return null
            }
        }

        private fun opcode(line: String, start: Int): Int? {
            var i = start
            while (i < line.length && (isIdentifierChar(line[i]) || line[i] == '$')) i++
            if (i >= line.length || line[i] != '=') return null
            val name = expand(line.substring(start, i))
            val valueStart = ++i
            val valueEnd = valueEnd(line, valueStart)
            val value = expand(line.substring(valueStart, valueEnd)).trim()
            when (name) {
                "default_path" -> defaultPath = value.replace('\\', '/')
                "sample" -> if (value.isNotEmpty() && !value.startsWith('*'))
                    resolve(defaultPath + value.replace('\\', '/'))?.let { found += it }
            }
            return valueEnd
        }

        /** Where sfizz ends an opcode value: at a header, or at whitespace followed by the end
         * of the line, a header, a directive, or the next `name=`. */
        private fun valueEnd(line: String, start: Int): Int {
            var p = start
            while (p < line.length) {
                if (line[p] == '<')
                    return p
                if (line[p] == ' ' || line[p] == '\t') {
                    var q = p
                    while (q < line.length && (line[q] == ' ' || line[q] == '\t')) q++
                    if (q == line.length || line[q] == '<')
                        return p
                    if (line[q] == '#' && (line.startsWith("define", q + 1) || line.startsWith("include", q + 1)))
                        return p
                    if (isIdentifierChar(line[q])) {
                        var r = q
                        while (r < line.length && (isIdentifierChar(line[r]) || line[r] == '$')) r++
                        if (r < line.length && line[r] == '=')
                            return p
                    }
                    p = q
                } else p++
            }
            return line.length
        }

        /** `$name` becomes the value of the shortest defined name it starts with; an undefined
         * one disappears. Repeats while values bring in more variables. */
        private fun expand(text: String): String {
            var src = text
            repeat(MAX_EXPANSION_ROUNDS) {
                if ('$' !in src) return src
                val dst = StringBuilder()
                var i = 0
                while (i < src.length) {
                    val c = src[i++]
                    if (c != '$') {
                        dst.append(c)
                        continue
                    }
                    val nameStart = i
                    var value: String? = null
                    while (i < src.length && isIdentifierChar(src[i]) && value == null) {
                        i++
                        value = defines[src.substring(nameStart, i)]
                    }
                    if (value != null) dst.append(value)
                }
                src = dst.toString()
            }
            return src
        }
    }
}

package org.androidaudioplugin.soundpack

import java.util.concurrent.ConcurrentHashMap

/** File types as users write them: wildcards on a file name, such as `*.sfz` or `*.inst`.
 * `*` matches any run of characters and `?` one character, ignoring case; `*.` matches names
 * without an extension (most of REAPER's JSFX). A pattern never contains `/`: it applies to the
 * file name in any subfolder. */
object FilePatterns {
    /** Names without an extension, as [PackPath.extension] defines it. */
    const val NO_EXTENSION = "*."

    private val compiled = ConcurrentHashMap<String, Regex>()

    fun isValid(pattern: String) = pattern.isNotBlank() && '/' !in pattern && pattern.trim() == pattern

    fun matches(pattern: String, fileName: String): Boolean = when {
        !isValid(pattern) -> false
        pattern == NO_EXTENSION -> PackPath.extension(fileName).isEmpty()
        else -> compiled.getOrPut(pattern) { toRegex(pattern) }.matches(fileName)
    }

    /** Whether any of [patterns] matches the file name of [path]. */
    fun matchesAny(patterns: List<String>, path: String): Boolean {
        val name = PackPath.fileName(path)
        return patterns.any { matches(it, name) }
    }

    /** Patterns typed as one line, separated by commas, semicolons or spaces. */
    fun parse(text: String): List<String> = text.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun format(patterns: List<String>) = patterns.joinToString(", ")

    private fun toRegex(pattern: String) = Regex(buildString {
        for (c in pattern)
            when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
    }, RegexOption.IGNORE_CASE)
}

package org.androidaudioplugin.soundpack.host

import android.content.Context
import android.content.SharedPreferences
import org.androidaudioplugin.soundpack.FilePatterns
import org.androidaudioplugin.soundpack.SoundpackContract
import org.androidaudioplugin.soundpack.SoundpackKind
import org.json.JSONArray
import org.json.JSONObject

/** The resource types an app offers for folders, kept in its settings: the default types
 * ([SoundpackKind.BUILT_IN]), as the user edited them, and the types the user added. Each has
 * a label, a description and file types ([FilePatterns]). The Soundpack Manager lets users edit
 * them; an app that never does gets the defaults.
 *
 * A type's id is part of every saved identity, so it never changes: an added type gets one
 * from its label when it is created. Added types are served by a [TypesFolderProvider]. */
class ResourceTypes private constructor(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("soundpack-resource-types", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    /** The default types first, then the added ones, in the order they were added. */
    fun all(): List<SoundpackKind> = SoundpackKind.BUILT_IN.map { kind(it.id)!! } + added()

    /** The types the user added. */
    fun added(): List<SoundpackKind> = read(KEY_ADDED).toList()

    /** A type as the user sees it now, or null when there is no such type. */
    fun kind(id: String): SoundpackKind? {
        val builtIn = SoundpackKind.builtIn(id) ?: return added().firstOrNull { it.id == id }
        return read(KEY_EDITED).firstOrNull { it.id == id }?.let { builtIn.withPresentation(it.label, it.filePatterns, it.description) } ?: builtIn
    }

    /** Whether a default type differs from how it ships. */
    fun isEdited(id: String) = read(KEY_EDITED).any { it.id == id }

    /** Adds a type. Files matching [filePatterns] are its entries, and every other file of its
     * folders is served too, as an app may need what is next to them (samples next to an
     * instrument, for example). */
    fun add(label: String, description: String, filePatterns: List<String>): SoundpackKind {
        val kind = SoundpackKind(newId(label), SoundpackKind.ReadScope.WHOLE_TREE, label.trim(), checked(filePatterns), description.trim())
        write(KEY_ADDED, added() + kind)
        return kind
    }

    /** Stores [kind]'s label, description and file types; how much of a folder it serves stays. */
    fun update(kind: SoundpackKind) {
        val current = requireNotNull(kind(kind.id)) { "No type ${kind.id}" }
        val edited = current.withPresentation(kind.label.trim(), checked(kind.filePatterns), kind.description.trim())
        if (SoundpackKind.builtIn(kind.id) != null)
            write(KEY_EDITED, read(KEY_EDITED).filter { it.id != kind.id }.toList() + edited)
        else
            write(KEY_ADDED, added().map { if (it.id == kind.id) edited else it })
    }

    /** A default type as it ships. */
    fun reset(id: String) = write(KEY_EDITED, read(KEY_EDITED).filter { it.id != id }.toList())

    /** Removes an added type; default types cannot be removed. */
    fun remove(id: String) {
        require(SoundpackKind.builtIn(id) == null) { "$id is a default type: reset it instead" }
        write(KEY_ADDED, added().filter { it.id != id })
    }

    /** Calls [listener] whenever a type changes in this process. */
    fun observe(listener: () -> Unit): AutoCloseable {
        synchronized(listeners) { listeners += listener }
        return AutoCloseable { synchronized(listeners) { listeners -= listener } }
    }

    private fun checked(filePatterns: List<String>): List<String> {
        val patterns = filePatterns.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(patterns.isNotEmpty()) { "A type needs at least one file type, such as *.wav" }
        patterns.firstOrNull { !FilePatterns.isValid(it) }?.let { throw IllegalArgumentException("Not a file type: $it") }
        return patterns
    }

    /** From the label: "My Sampler" becomes `my.sampler`, unless that is taken. */
    private fun newId(label: String): String {
        val words = label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val base = words.joinToString(".").take(100).ifEmpty { "type" }
        val taken = all().map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base.$it" }
            .first { SoundpackKind.isValidId(it) && it !in taken }
    }

    private fun read(key: String): Sequence<SoundpackKind> {
        val array = runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).asSequence().mapNotNull { index ->
            runCatching {
                val json = array.getJSONObject(index)
                SoundpackKind(json.getString("id"), SoundpackContract.Types.readScopeOf(json.optString("readScope")),
                    json.getString("label"), FilePatterns.parse(json.getString("filePatterns")), json.optString("description"))
            }.getOrNull()
        }
    }

    private fun write(key: String, kinds: List<SoundpackKind>) {
        val array = JSONArray()
        kinds.forEach {
            array.put(JSONObject().put("id", it.id).put("label", it.label).put("description", it.description)
                .put("filePatterns", it.filePatterns.joinToString(" "))
                .put("readScope", SoundpackContract.Types.readScopeValue(it.readScope)))
        }
        preferences.edit().putString(key, array.toString()).commit()
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    companion object {
        private const val KEY_ADDED = "added"
        private const val KEY_EDITED = "edited"

        @Volatile
        private var instance: ResourceTypes? = null

        fun get(context: Context): ResourceTypes =
            instance ?: synchronized(this) { instance ?: ResourceTypes(context).also { instance = it } }
    }
}

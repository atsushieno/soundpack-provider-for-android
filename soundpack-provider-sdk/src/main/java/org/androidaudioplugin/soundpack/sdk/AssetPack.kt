package org.androidaudioplugin.soundpack.sdk

import android.content.res.XmlResourceParser
import org.androidaudioplugin.soundpack.PackGlob
import org.androidaudioplugin.soundpack.PackPath
import org.androidaudioplugin.soundpack.SoundpackContract
import org.xmlpull.v1.XmlPullParser

/** A pack shipped as APK assets. Everything under [assetRoot] is the pack, and nothing
 * outside it is served: the root is the whole sharing boundary.
 *
 * Bump [revision] whenever any file's bytes or names change; clients pin immutable packs to
 * a revision. */
class AssetPack(
    val id: String,
    val label: String,
    val revision: String,
    val assetRoot: String,
    /** Explicit entries; when both these and [entryPatterns] are empty, every file the kind
     * considers an entry candidate is one. */
    val entries: List<Entry> = emptyList(),
    val entryPatterns: List<EntryPattern> = emptyList(),
    val summary: String? = null,
    val license: String? = null,
) {
    init {
        require(SoundpackContract.isValidPackId(id)) { "Invalid pack id: $id" }
        require(SoundpackContract.isValidRevision(revision)) { "Invalid revision: $revision" }
        // The asset root itself holds framework directories (images, webkit…), so a pack needs its own.
        require(PackPath.normalize(assetRoot) == assetRoot) { "Invalid or empty asset root: '$assetRoot'" }
    }

    class Entry(val path: String, val label: String? = null, val group: String? = null, val scopes: List<String> = emptyList()) {
        init {
            require(PackPath.normalize(path) == path) { "Entry path is not normalized: $path" }
        }
    }

    class EntryPattern(val glob: PackGlob, val group: String? = null, val scopes: List<String> = emptyList())

    companion object {
        /** Reads the `@xml/` pack declaration:
         *
         * ```xml
         * <packs>
         *   <pack id="org.example.piano" label="Piano" revision="1" assetRoot="piano"
         *         summary="…" license="CC0">
         *     <entry path="Piano.sfz" label="Grand" group="Keys" scopes="samples" />
         *     <entries pattern="Kits/kit-?.sfz" group="Drums" />
         *   </pack>
         * </packs>
         * ``` */
        fun parse(parser: XmlResourceParser): List<AssetPack> {
            val packs = mutableListOf<AssetPack>()
            var current: MutableMap<String, String?>? = null
            val entries = mutableListOf<Entry>()
            val patterns = mutableListOf<EntryPattern>()
            fun attribute(name: String): String? = parser.getAttributeValue(null, name)
            fun scopes() = attribute("scopes")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> return packs
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "pack" -> {
                            current = listOf("id", "label", "revision", "assetRoot", "summary", "license")
                                .associateWith { attribute(it) }.toMutableMap()
                            entries.clear()
                            patterns.clear()
                        }
                        "entry" -> entries += Entry(requireNotNull(attribute("path")) { "<entry> needs path" },
                            attribute("label"), attribute("group"), scopes())
                        "entries" -> patterns += EntryPattern(PackGlob(requireNotNull(attribute("pattern")) { "<entries> needs pattern" }),
                            attribute("group"), scopes())
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "pack") {
                        val pack = requireNotNull(current)
                        val id = requireNotNull(pack["id"]) { "<pack> needs id" }
                        packs += AssetPack(
                            id = id,
                            label = pack["label"] ?: id,
                            revision = requireNotNull(pack["revision"]) { "<pack> $id needs revision" },
                            assetRoot = requireNotNull(pack["assetRoot"]) { "<pack> $id needs assetRoot" },
                            entries = entries.toList(),
                            entryPatterns = patterns.toList(),
                            summary = pack["summary"],
                            license = pack["license"],
                        )
                        current = null
                    }
                }
            }
        }
    }
}

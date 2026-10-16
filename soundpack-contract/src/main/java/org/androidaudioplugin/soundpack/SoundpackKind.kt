package org.androidaudioplugin.soundpack

/** A kind of content, or resource type. Each built-in or app-declared kind has its own
 * discovery action and its own provider authority, so content of different kinds never shares
 * a provider, and grants never cross kinds (docs/PLAN.md §3.2). Kinds the user defines in the
 * Soundpack Manager are served together through [SoundpackContract.Types].
 *
 * Kinds are equal when their ids are: the label, file types and description are presentation,
 * and the user may edit them. */
class SoundpackKind(
    /** Lower-case; built-in kinds are short (`sfz`), third-party kinds are reverse-DNS. It is
     * part of every saved identity, so it never changes. */
    val id: String,
    /** What a client may read from a pack of this kind. */
    val readScope: ReadScope,
    /** Shown to users, e.g. "SFZ". */
    val label: String = id,
    /** Which files of a folder are entries ([FilePatterns]); APK packs may list theirs. */
    val filePatterns: List<String> = emptyList(),
    /** Shown to users next to the kind when not empty. */
    val description: String = "",
) {
    init {
        require(isValidId(id)) { "Invalid kind id: $id" }
    }

    enum class ReadScope {
        /** Every file of the pack except dot-files: SFZ samples and includes, JSFX imports. */
        WHOLE_TREE,
        /** Only the entry files: an SF2 is self-contained. */
        ENTRIES_ONLY,
    }

    /** The intent action providers of this kind declare and clients discover by. */
    val action: String get() = "${SoundpackContract.NAMESPACE}.$id.V1"

    /** `${applicationId}.soundpack.<kind>`: a public or hub provider. */
    fun authorityFor(applicationId: String) = "$applicationId.soundpack.$id"

    /** `${applicationId}.soundpack.local.<kind>`: an app's private folder provider. */
    fun localAuthorityFor(applicationId: String) = "$applicationId.soundpack.local.$id"

    /** Whether [authority] follows either convention for this kind. */
    fun matchesAuthority(authority: String) =
        authority.endsWith(".soundpack.$id") || authority.endsWith(".soundpack.local.$id")

    fun isEntryCandidate(path: String) = FilePatterns.matchesAny(filePatterns, path)

    val isBuiltIn: Boolean get() = BUILT_IN.any { it.id == id }

    /** The same kind with other presentation, as the user edited it. */
    fun withPresentation(label: String = this.label, filePatterns: List<String> = this.filePatterns,
                         description: String = this.description, readScope: ReadScope = this.readScope) =
        SoundpackKind(id, readScope, label, filePatterns, description)

    override fun toString() = id
    override fun equals(other: Any?) = other is SoundpackKind && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        // Declared before the kinds below, whose constructors use them.
        private val idPattern = Regex("[a-z0-9]+(\\.[a-z0-9]+)*")
        /** `.soundpack.types` and `.soundpack.local.*` authorities mean something else. */
        private val reservedIds = setOf("types", "local")

        val SFZ = SoundpackKind("sfz", ReadScope.WHOLE_TREE, "SFZ", listOf("*.sfz"),
            "Instruments for SFZ players such as AAP Sfizz. The whole folder is served, so samples and includes can be anywhere in it.")

        val SF2 = SoundpackKind("sf2", ReadScope.ENTRIES_ONLY, "SF2", listOf("*.sf2", "*.sf3"),
            "SoundFonts for SF2 players such as USF2Plugin. Only the SoundFont files are served.")

        /** REAPER's own effects mostly have no extension; `.jsfx-inc` files are imports. Only
         * candidates: the client's JSFX parser decides what is an effect. */
        val JSFX = SoundpackKind("jsfx", ReadScope.WHOLE_TREE, "JSFX", listOf("*.jsfx", FilePatterns.NO_EXTENSION),
            "Effects for JSFX hosts such as uapmd. The whole folder is served, so imports and data files can be next to the effects.")

        /** Audio an AAP host plays through a plugin to try it (aap-core#165). */
        val HOST_SAMPLE = SoundpackKind("hostsample", ReadScope.ENTRIES_ONLY, "AAP Sample", listOf("*.wav"),
            "Audio that AAP hosts play through a plugin to try it out.")

        val BUILT_IN = listOf(SFZ, SF2, JSFX, HOST_SAMPLE)

        fun isValidId(id: String) = id.length <= 128 && idPattern.matches(id) && id !in reservedIds

        fun builtIn(id: String): SoundpackKind? = BUILT_IN.firstOrNull { it.id == id }

        /** The kind of a saved identity: a built-in one, or one known only by its id, such as a
         * kind the user defined in the Soundpack Manager. Clients need no more than the id. */
        fun forId(id: String): SoundpackKind = builtIn(id) ?: SoundpackKind(id, ReadScope.ENTRIES_ONLY)
    }
}

package org.androidaudioplugin.soundpack

/** A kind of content. Each kind has its own discovery action and its own provider authority,
 * so content of different kinds never shares a provider, and grants never cross kinds
 * (docs/PLAN.md §3.2). */
class SoundpackKind(
    /** Lower-case; built-in kinds are short (`sfz`), third-party kinds are reverse-DNS. */
    val id: String,
    /** What a client may read from a pack of this kind. */
    val readScope: ReadScope,
    /** Decides which files of a folder are offered as entries (APK packs may list them). */
    private val entryCandidate: (path: String) -> Boolean,
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

    fun isEntryCandidate(path: String) = entryCandidate(path)

    override fun toString() = id
    override fun equals(other: Any?) = other is SoundpackKind && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        // Declared before the kinds below, whose constructors use it.
        private val idPattern = Regex("[a-z0-9]+(\\.[a-z0-9]+)*")

        val SFZ =SoundpackKind("sfz", ReadScope.WHOLE_TREE) { PackPath.extension(it) == "sfz" }

        val SF2 = SoundpackKind("sf2", ReadScope.ENTRIES_ONLY) { PackPath.extension(it) in setOf("sf2", "sf3") }

        /** REAPER's own effects mostly have no extension; `.jsfx-inc` files are imports. Only
         * candidates: the client's JSFX parser decides what is an effect. */
        val JSFX = SoundpackKind("jsfx", ReadScope.WHOLE_TREE) {
            PackPath.extension(it).let { extension -> extension == "jsfx" || extension.isEmpty() }
        }

        val BUILT_IN = listOf(SFZ, SF2, JSFX)

        fun isValidId(id: String) = id.length <= 128 && idPattern.matches(id)

        fun builtIn(id: String): SoundpackKind? = BUILT_IN.firstOrNull { it.id == id }
    }
}

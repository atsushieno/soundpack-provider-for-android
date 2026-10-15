package org.androidaudioplugin.soundpack

/** The wire contract shared by every soundpack provider and client: the names that end up in
 * manifests, content URIs, cursors and saved projects (docs/PLAN.md §3.2–§3.3).
 *
 * Provisional until the V1 freeze. */
object SoundpackContract {
    /** Reported by `call(METHOD_INFO)`. */
    const val PROTOCOL_VERSION = 1

    /** Prefix of every Android-side identifier: actions, `<meta-data>` names (D3). */
    const val NAMESPACE = "org.androidaudioplugin.soundpack"

    /** `<meta-data>` on a pack `<provider>`, naming the `@xml/` resource that declares its packs. */
    const val META_DATA_PACKS = "$NAMESPACE#Packs"

    /** Scheme of saved identities; see [SoundpackIdentity]. */
    const val IDENTITY_SCHEME = "commons-soundpack"

    const val PATH_PACKS = "packs"
    const val PATH_ENTRIES = "entries"
    const val PATH_FILES = "files"
    const val PATH_REVISION = "rev"

    const val METHOD_INFO = "info"
    const val METHOD_RESCAN = "rescan"
    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_KIND = "kind"

    const val SOURCE_APK = "apk"
    const val SOURCE_FOLDER = "folder"

    /** The Soundpack Manager (the hub, docs/PLAN.md §3.6). Its providers follow the usual
     * `<package>.soundpack.<kind>` authorities, and every app can read them like a pack app's:
     * a folder shared there reaches every soundpack app. */
    object Hub {
        const val PACKAGE = "org.androidaudioplugin.soundpack.manager"
        /** Shares a folder an app has linked: the tree URI is in the ClipData, granted READ |
         * PERSISTABLE | PREFIX (S6), and [EXTRA_KINDS] proposes kinds. The hub asks the user. */
        const val ACTION_SHARE_FOLDER = "$NAMESPACE.action.SHARE_FOLDER"
        const val EXTRA_KINDS = "$NAMESPACE.extra.KINDS"
    }

    object Packs {
        const val PACK_ID = "pack_id"
        const val LABEL = "label"
        const val REVISION = "revision"
        const val SOURCE = "source"
        /** 1 when the content can change without a revision bump (folders), else 0. */
        const val LIVE = "live"
        const val FILE_COUNT = "file_count"
        const val TOTAL_BYTES = "total_bytes"
        const val SUMMARY = "summary"
        const val LICENSE = "license"
        val COLUMNS = arrayOf(PACK_ID, LABEL, REVISION, SOURCE, LIVE, FILE_COUNT, TOTAL_BYTES, SUMMARY, LICENSE)
    }

    object Entries {
        const val ENTRY_PATH = "entry_path"
        const val LABEL = "label"
        /** "/"-separated location used to present entries as a tree. */
        const val GROUP_PATH = "group_path"
        /** Space-separated pack directories the entry's resources live in; a hint, may be empty. */
        const val SCOPES = "scopes"
        /** Kind-specific JSON, e.g. SF2 presets; may be null. */
        const val METADATA = "metadata"
        val COLUMNS = arrayOf(ENTRY_PATH, LABEL, GROUP_PATH, SCOPES, METADATA)
    }

    object Files {
        const val PATH = "path"
        const val SIZE = "size"
        const val LAST_MODIFIED = "last_modified"
        val COLUMNS = arrayOf(PATH, SIZE, LAST_MODIFIED)
    }

    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** Pack ids are global (reverse-DNS, or `saf-<hash>` for folders), so they are restricted
     * to characters that survive every URI and file system unencoded. */
    fun isValidPackId(id: String) = idPattern.matches(id)

    fun isValidRevision(revision: String) = idPattern.matches(revision)

    /** A request against a provider, decoded from a content URI's path segments. */
    sealed interface Route {
        data object Packs : Route
        data class Entries(val packId: String) : Route
        data class Files(val packId: String) : Route
        /** [path] is joined but *not* normalized; providers must normalize it ([PackPath]). */
        data class File(val packId: String, val revision: String, val path: String) : Route
    }

    fun packsSegments(): List<String> = listOf(PATH_PACKS)
    fun entriesSegments(packId: String) = listOf(PATH_PACKS, packId, PATH_ENTRIES)
    fun filesSegments(packId: String) = listOf(PATH_PACKS, packId, PATH_FILES)
    fun fileSegments(packId: String, revision: String, path: String) =
        listOf(PATH_PACKS, packId, PATH_REVISION, revision, PATH_FILES) + path.split('/')

    /** Decodes already percent-decoded path segments, as `Uri.getPathSegments()` returns them. */
    fun route(segments: List<String>): Route? {
        if (segments.firstOrNull() != PATH_PACKS)
            return null
        if (segments.size == 1)
            return Route.Packs
        val packId = segments[1].takeIf(::isValidPackId) ?: return null
        return when {
            segments.size == 3 && segments[2] == PATH_ENTRIES -> Route.Entries(packId)
            segments.size == 3 && segments[2] == PATH_FILES -> Route.Files(packId)
            segments.size >= 6 && segments[2] == PATH_REVISION && segments[4] == PATH_FILES ->
                segments[3].takeIf(::isValidRevision)?.let { Route.File(packId, it, segments.drop(5).joinToString("/")) }
            else -> null
        }
    }
}

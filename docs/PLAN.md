Soundpack Manager for Android: survey and implementation plan
============================================================

Drafted 2026-10-15 from these checkouts:

| Repository | Revision | Location |
| --- | --- | --- |
| aap-lv2-sfizz | `7453717` | `~/sources/AAP/aap-lv2-sfizz` |
| aap-sfz-vpo3 | `74a57cd` | `~/sources/extssd/AAP/aap-sfz-vpo3` |
| aap-sfz-salamander-drumkit | `d3a7e93` | `~/sources/extssd/AAP/aap-sfz-salamander-drumkit` |
| aap-juce-usf2plugin | `3ffd0af` (+ `aap-juce-support.patch` on usf2plugin `3a08fa8`) | `~/sources/extssd/AAP/aap-juce-usf2plugin` |
| uapmd | `c6308865` | `~/sources/uapmd` |

This document is the plan. The platform behaviours it marked **[spike]** were checked by the
Phase 0 spikes on a Samsung Galaxy Tab A11+ running Android 16 (2026-10-15). Each marker
now states its result; details are in `docs/spikes.md`. The same results were reproduced on an
Android 15 emulator (arm64; this Mac cannot run x86_64 images).


1. Goal
-------

One Android app, the **Soundpack Manager** (the "hub"), and a set of libraries that give
audio apps uniform, read-only access to sound content that lives outside the app:

- packs published by other installed apps (sfizz's "SFZ provider" APKs today),
- folders the user picked through the Storage Access Framework (SAF),

Getting content onto the device is the user's business. They push it with ADB, copy it over
MTP, or use a file manager. The host never uploads content. It takes a folder that is already
on the device, in the hub or as a local folder inside an app, and either **imports** it (a
copy in the app's storage) or uses it **in place**; the user chooses per folder (D6).

The point is that sfizz, usf2plugin, uapmd and future apps stop each owning a folder list,
a SAF walker, a picker UI and a provider client. One content kind never shares a content
provider with another: each kind has its own discovery action and its own authority.

Deliverables:

1. the hub app (Kotlin, Compose);
2. a **contract** library: kinds, URI layout, columns, identities;
3. a **provider SDK** for pack APK authors (it replaces `sfz-provider`);
4. a **client library** in Kotlin, with a thin C API for native engines and a Compose picker;
5. a **host core** library: SAF folder sources, the index, the folder-backed providers and
   the Compose folder-management screen. The hub is built from it, and every client app can
   embed it.

**The hub is never required.** Any app can manage local folders by itself through the
embedded host core, exactly as sfizz, usf2plugin and uapmd do today, but with shared code.
What the hub adds is one folder registry shared by all apps: register a folder once, scan it
once, and every soundpack app sees it (D5).

None of these depend on aap-core. uapmd is not an AAP app, and the libraries must work for it.


2. What exists today
--------------------

### 2.1 aap-lv2-sfizz (SFZ)

The most developed of the three. Its design notes are in `docs/external-sfz-plan.md`.

- **Pack APK contract.** `:sfz-provider` defines a bound-service AIDL contract:
  `ISfzResourceService` (`getProtocolVersion`, paged `listInstruments` returning Bundles,
  `openInstrument(id, revision)`) and `ISfzResourceSession` (`getEntryPath`,
  `listResources`, `openResource` returning `AssetFileDescriptor`, `close`). It is
  discovered through the action `org.androidaudioplugin.SfzResourceService.V1`.
  `AssetSfzResourceService` implements the contract over uncompressed APK assets.
- **Client.** `SfzResourceClient` binds each provider, waits up to 10 s on a latch, pages the
  catalogue and builds opaque identities: `aap-sfz://pkg/class/id?revision=r` for provider
  packs and `aap-sfz-folder://local?tree=…&entry=…` for folders.
- **Folders.** `SfzFolders` and `SfzFoldersActivity` keep persisted tree grants in
  SharedPreferences. They walk the whole tree with `DocumentsContract` queries, skip
  dot-files, and reject unsafe names. On open, a seekable regular-file FD is passed through
  as is. A pipe or cloud stream is copied to a temporary file holding just that one file.
- **UI.** `SfzTree.kt` groups the flat choice list into a collapsible tree, and
  `SfzResourceViewFactory` renders it above the parameter view inside
  `AudioPluginViewService`. `SfzFoldersActivity` exists so a service-hosted UI can still
  obtain an activity result. (S5 later showed that this works only when sfizz's own
  activity shows the UI. In an external host on Android 14+, the start is blocked.)
- **Native side.** `native/AudioPluginLV2Resources_jni.cpp` validates each
  `AssetFileDescriptor` range (`S_ISREG`, bounds), page-aligns the offset, and `mmap`s it
  `PROT_READ`/`MAP_PRIVATE`. It then closes the FD and keeps the mapping alive for the
  pack's lifetime. APK packs are mapped eagerly; folder packs are opened lazily per resource.
- **State.** sfizz state stores the identity string, not a path. If the reopen fails on
  restore, the instrument goes into a recoverable "unavailable" state.

### 2.2 SFZ pack APKs (vpo3, salamander-drumkit)

Both repositories vendor a copy of `sfz-provider`, and the three copies have already
diverged:

- the sfizz copy added an optional `path` Bundle key used for tree display;
- the vpo3 copy added `Instrument.resourcePaths`, which limits the files an instrument
  exposes to its family folder plus `libs`;
- the salamander copy has neither change.

This is the first duplication the new libraries must remove: the contract has to be a
published artifact, not copied source.

### 2.3 aap-juce-usf2plugin (SF2)

On Android, `aap-juce-support.patch` adds the following to upstream usf2plugin:

- `SoundFontDirectory` holds either a `juce::File` or a SAF tree URL. Folders are added with
  `AndroidDocumentPermission::takePersistentReadWriteAccess`; read-only access would be
  enough.
- `SoundFontLibrary::run` walks trees through `AndroidDocumentIterator`. On every scan it
  opens **every** `.sf2` file to read its preset headers.
- `findDocument` resolves a saved relative path by iterating directories one name at a time.
- `SoundFontFile::loadData` reads the **whole** SF2 into a `juce::MemoryBlock` heap copy
  before loading it with TinySoundFont.
- State stores the document URL in `sf2Absolute`.
- `DirectorySettingsPanel` has an Android branch for picking folders.

### 2.4 uapmd (JSFX)

- `uapmd-file/IDocumentProvider` is a cross-platform abstraction. On Android,
  `DocumentProviderAndroid.cpp` implements `pickFolder`, `listFolderDocuments` and
  `releaseFolder` over `OPEN_DOCUMENT_TREE` entirely in JNI.
- ysfx opens scripts, imports and data files **by path**, so `JsfxUserContent.cpp` mirrors
  every registered folder into app storage under `jsfxFolderMirrorRoot()`.
- `AppModel::syncJsfxRegisteredFolders` runs before each plugin scan. It clears each mirror
  and re-copies every file of every folder. JSFX content is small, but the work grows with
  library size, and none of it is incremental.
- `JsfxResourcesWindow` is a third folder-management UI, written in ImGui.

### 2.5 What to keep and what to drop

Keep the ideas that sfizz validated:

- revisioned, immutable packs;
- `AssetFileDescriptor` ranges, so APK assets are never extracted;
- `mmap` instead of heap copies;
- lazy per-file open;
- a pack root as the sharing boundary, with lexical `..` allowed inside it;
- skipping hidden files;
- copying only the requested file when the source is a pipe;
- state saved as identities, with a recoverable "unavailable" state.

Drop the following, because shared libraries can do each of them once. The host core does
the folder work, whether it runs in the hub or embedded in an app:

- three folder lists and three folder UIs;
- the full SAF tree walk that sfizz repeats on every `discover()` **and** every `open()`;
- usf2plugin opening every SF2 on every scan;
- uapmd re-copying every file on every scan;
- the vendored copies of the contract;
- the bind/latch/timeout machinery around each catalogue call.


3. Architecture
---------------

```text
 Pack APKs (VPO3, Salamander, …)       Soundpack Manager (hub, optional)
   provider SDK:                         host core: SAF trees, index DB
   <provider …soundpack.sfz>             <provider …soundpack.sfz|sf2|jsfx>
   AssetPackProvider (public read)       exported, public read (D5)
            │                                        │
            └─── ContentResolver: query / openAssetFileDescriptor / call ───┐
                                                                            ▼
 Consumer app (aap-lv2-sfizz plugin process, usf2plugin, uapmd …)
   soundpack-client (Kotlin): discovery, access state, catalog Flow, PackSession, materialize
   soundpack-native (C ABI over JNI): entries, sessions, fds, mmap helper, host folders
   soundpack-ui-compose: picker tree
   embedded host core (opt-in per kind):
     <provider …soundpack.local.sfz>  not exported; this app's own folders, no hub needed
```

From a client's point of view, every source is **just another provider** of a kind:

- **Pack APKs** are public, discovered by action, and read directly. They work without the hub.
- **The app's own local provider** serves the folders the user registered in this app. It is
  not exported, so no other app can see it, and it needs no consent.
- **The hub's provider**, when installed, serves the shared registry, like any pack.

The catalog merges all three. A folder registered both locally and in the hub has the same
pack id (§3.7), so it appears once.

### 3.1 Transport: ContentProvider, not a bound AIDL service

I recommend replacing the `SfzResourceService.V1` AIDL with one ContentProvider per kind:

| Concern | Bound AIDL service (today) | ContentProvider (proposed) |
| --- | --- | --- |
| Connection | bind, wait on a latch, time out, unbind on every catalogue or open call | none; the provider process starts on demand |
| Large indexes | hand-paged `Bundle[]` | `Cursor`; cross-process windows page automatically |
| Descriptor ranges | AIDL returns `AssetFileDescriptor` | `openAssetFile` returns an AFD with offset and length (S3 confirmed: a non-page-aligned APK sub-range arrives intact and maps) |
| Access to private data | no model | standard URI grants, persistable, revocable |
| Change notification | none | `ContentObserver` / `notifyChange` |
| Extra operations | add more AIDL methods | `call(method, arg, extras)` |
| Native use | needs a Java object bridge | JNI to `ContentResolver`, or through the Kotlin client |

The AIDL "session" (an immutable namespace of resources) maps onto a URI that carries the
revision. Once a client holds an FD, it stays valid even if the pack is upgraded or the
provider process dies. That is the lifetime sfizz already relies on.

### 3.2 Content kinds, and why per-kind providers are not tedious

Each kind is defined once, in the contract module:

| Kind | Discovery action | Authority (by convention) | Entries in a folder | Resource scope | Kind metadata |
| --- | --- | --- | --- | --- | --- |
| `sfz` | `org.androidaudioplugin.soundpack.sfz.V1` | `${applicationId}.soundpack.sfz` | `*.sfz` (packs may list them explicitly) | whole pack tree: includes, samples, `..` within root | none |
| `sf2` | `org.androidaudioplugin.soundpack.sf2.V1` | `${applicationId}.soundpack.sf2` | `*.sf2`, `*.sf3` anywhere in the tree; users manage SF2s as folders, and the folder hierarchy is the browsing structure | the entry files; apps read whole SF2s, and other files in the tree are not served | preset list (bank, program, name) from PHDR |
| `jsfx` | `org.androidaudioplugin.soundpack.jsfx.V1` | `${applicationId}.soundpack.jsfx` | candidates only; the client's parser decides | whole pack tree: imports, data, gfx | `desc:` hint |
| `hostsample` ("AAP Sample") | `org.androidaudioplugin.soundpack.hostsample.V1` | `${applicationId}.soundpack.hostsample` | `*.wav`: audio an AAP host plays through a plugin to try it (aap-core#165) | the entry files | none |

Each kind also has a label, a description and its file types as wildcards (`*.sfz`; `*.`
for names without an extension), which users can edit in the Soundpack Manager (D7).

The URIs are tedious only if someone writes them by hand. Here nobody does:

- An authority is always `${applicationId}.soundpack.<kind>`. Manifest placeholders expand it.
- An app's private folder provider uses `${applicationId}.soundpack.local.<kind>`. It has no
  intent-filter and is not exported. Because the suffix differs, an app can embed local
  folders *and* publish a public pack of the same kind. For example, sfizz can keep
  advertising its bundled piano while managing the user's SFZ folders privately.
- Clients never hard-code an authority. They discover `ProviderInfo` by action, and all URIs
  come from `SoundpackContract.uris(kind, authority)`.
- A pack APK declares one `<provider>` per kind it serves. The SDK supplies the class, so the
  declaration is about six lines plus an XML resource.
- Third-party kinds (wavetables, IRs or NAM models for aap-juce-tone3000/byod, Scala tunings,
  DX7 cartridges for dexed) follow the same convention with a reverse-DNS kind id. They need
  no change to the hub's protocol; the hub UI only needs to know their file patterns.
- Resource types a user adds in the Soundpack Manager, for an app with a format of its own,
  cannot declare providers. One provider per app serves them all (D7):
  `${applicationId}.soundpack.types`, action `org.androidaudioplugin.soundpack.types.V1`.
  `types` lists them (id, label, description, file types, read scope), and every other URI
  is the usual layout under `types/<kind id>/`. `SoundpackClient` reaches them by kind id,
  and `SoundpackTypes.list()` returns every type an app can find.

Each kind has its own provider, so a folder linked only as SFZ is not served as JSFX or SF2
(D6).

### 3.3 URI layout and columns (V1, provisional)

```text
content://<auth>/packs                                     query        → packs
content://<auth>/packs/<packId>/entries                    query        → entries
content://<auth>/packs/<packId>/files                      query        → file index
content://<auth>/packs/<packId>/rev/<rev>/files/<path…>    openAssetFile → read-only AFD
call(<auth>, "info")              → protocolVersion, kind, capabilities
call(<auth>, "rescan", packId?)   → folder providers only (hub or local)
```

- `packs`: `pack_id`, `label`, `revision`, `source` (`apk` | `folder`), `live`
  (folder content can change), `file_count`, `total_bytes`, `summary`, `license`.
- `entries`: `entry_path`, `label`, `group_path` (generalises sfizz's `path` key, for tree
  display), `scopes` (optional; generalises vpo3's `resourcePaths`), `metadata` (JSON,
  kind-specific).
- `files`: `path`, `size`, `last_modified`.

Rules carried over from sfizz:

- Paths are `/`-separated and relative to the pack.
- Lexical `..` is allowed only while the path stays inside the root. Absolute paths and
  escapes are rejected.
- Dot-files are not listed.
- Limits: depth 64, 65,536 files per pack, 4,096-byte paths.
- A request for a non-current revision fails with `FileNotFoundException("revision")`.

### 3.4 Delivery guarantees

Native clients can rely on four guarantees about every `openAssetFile` result:

1. It is a read-only FD of a **regular, seekable file**, with a finite `declaredLength` and a
   64-bit `startOffset`.
2. APK packs return `assets.openFd()` sub-ranges. Exposing those leaks nothing new, because
   APKs are world-readable.
3. When a folder provider (the hub's, or an app's local one) serves a SAF document and the
   underlying provider gives a regular file (ExternalStorageProvider does), it passes the FD
   through. It fills in `declaredLength` from `fstat`, because ExternalStorageProvider
   reports −1 (S4).
4. When the underlying provider gives a pipe or a cloud stream, the **folder provider**
   copies that one file into its own cache and serves the copy. Clients therefore never
   handle pipes, and sfizz's spill-to-cache code moves into the host core.

The native helper in `soundpack-native` is sfizz's mapping code, generalised: validate,
page-align, `mmap(PROT_READ, MAP_PRIVATE)`, close the FD, and own the mapping until released.
usf2plugin then hands TinySoundFont a mapped pointer instead of a heap copy.

### 3.5 Engines that need real paths (JSFX)

ysfx needs files on disk, so the client provides
`materialize(kind, packId, destDir): MaterializeResult`:

- It compares the pack's file index (`path`, `size`, `last_modified`) against a manifest kept
  in `destDir`.
- It copies only files that are new or changed and deletes files that have gone.
- It writes atomically, through a temporary file and a rename.

uapmd replaces "clear the mirror, then re-copy everything" with this call, keeping the mirror
location it already has. Other path-only engines can use the same call.

### 3.6 Access

- **Pack APK providers** are exported with no permission, because their content is public.
- **An app's local providers** are not exported. Only the app itself reads them; the user
  consented by picking the folder in that app.
- **The hub's providers** are exported with no permission too (D5). Sharing a folder in the
  Soundpack Manager, or with it from an app ("Share with Soundpack Manager", §3.9), publishes
  the folder to every soundpack app, as the kinds it was linked for (D6). There is no connect
  step: an app reads the hub like any pack, and the hub is simply another source.

S2 confirmed that a persistable prefix grant on the root of a custom provider works across
processes, upgrades and reboots, and can be revoked. The per-app consent flow of Phase 3 was
built on it; D5 removed that flow.

### 3.7 Identities and saved state

```text
commons-soundpack://<kind>/<packId>/<entryPath>?rev=<revision>&via=<authority>
```

The `commons-` scheme says the identity is meant for common use. It names content, not an
Android component or an AAP feature, so a saved project stays meaningful to any app and on
any platform, including uapmd projects moved between desktop and Android. The prefix also
keeps the scheme distinct from a bare `soundpack://` someone else might use. The Android
wiring (actions, authorities, `<meta-data>` names) stays under
`org.androidaudioplugin.soundpack` (D3).

- `packId` is global for packs that declare one (reverse-DNS, e.g. `org.studiorack.vpo3`).
  Packs can therefore resolve through whichever provider currently serves them, which lets
  uapmd projects move between desktop and Android.
- A folder's `packId` is **derived from the folder**, never assigned at random:
  `saf-<hash(SAF provider authority + tree document id)>`. Built from
  `DocumentsContract.getTreeDocumentId`, it is the same whether the app's local provider or
  the hub serves the folder, so a folder can move between them without breaking saved
  states. Renaming or moving the folder changes the id; sfizz already behaves this way, since
  it stores the tree URI. A folder can instead declare a portable id with an optional
  `soundpack.json` at its root.
- `via` is a hint, not a requirement.
- **Revision policy.** Immutable (APK) packs default to *exact*: a missing revision means
  "unavailable", which is sfizz's current behaviour. Live (folder) packs default to
  *latest*, so adding one sample to a folder does not break every saved project.
- **No legacy identities** (D4). States saved by sfizz 0.6.2 or earlier, which contain
  `aap-sfz://…` or `aap-sfz-folder://…`, restore in the existing "unavailable" state; the
  user reselects the instrument. Folder *registrations* still carry over: on first launch,
  sfizz registers its existing `sfz-folders` SharedPreferences trees with its local
  provider, reusing the grants it already holds. That is a registration, not a URI, so the
  user re-picks no folders.

### 3.8 Discovery, change notification and threading

- Discovery uses `queryIntentContentProviders(Intent(action), GET_META_DATA)`. The client AAR
  manifest carries `<queries>` for each kind's action.
  **S1 confirmed:** `<queries><intent>` makes a provider's intent-filter visible to
  `queryIntentContentProviders`, while an undeclared action stays hidden. The marker-service
  fallback is not needed.
- `SoundpackClient.packs(kind): Flow<List<Pack>>` re-queries when a `ContentObserver` fires
  on `/packs` or a package is added, replaced or removed. The latter uses a context-registered
  receiver while the flow is collected.
- Every client call is blocking IPC and must run on an IO thread. The audio thread never
  touches this library; that stays a documented rule, as it is in sfizz.
- Native code may call the C API from any thread that is not an audio thread.
  `soundpack_init()` caches global references to classes and methods on a Java thread, so
  later calls never `FindClass` from native threads. The AAR ships consumer R8 rules for
  everything called through JNI.
- The hub's `Application` and `ContentProvider.onCreate` must stay trivial, because each cold
  open may start the hub's process. The same applies to the local providers: Android creates
  every declared provider when the embedding app's process starts, including an AAP plugin's
  `AudioPluginService` process. The index database opens lazily on the first query.

### 3.9 Host core: shared by the hub and by embedding apps

The folder machinery is written once, as a library. The hub and every embedding app run the
same code; only the provider's exposure differs.

- **Sources** are linked folders only: a persisted SAF tree grant. The user chooses the kind
  first and then picks a folder for it (D6); nothing is guessed from the folder's content,
  and a folder can be linked again for another kind. An embedding app only offers the kinds
  it declared a local provider for.
- **Import or in place** (D6), per folder:
  - An imported folder is copied to `noBackupFilesDir/soundpack-imports/<pack id>`, only what
    its kinds serve: the whole tree for SFZ and JSFX, the entries for SF2. Its files then
    open as plain files of the app, like an APK's assets, with no SAF on the load path.
  - Every scan brings the copy up to date: new and changed files are copied, 4 at a time, and
    removed ones are deleted. A file that cannot be copied is served from the folder, and the
    folder shows why (no space, for example).
  - When the folder can no longer be read, the copy keeps serving. Using the folder in place
    again, or removing it, deletes the copy.
  - A folder used in place keeps its files where the user put them, and every open goes
    through SAF.
- **Picker limits.** Since Android 11, the tree picker refuses the root of storage, the root
  of `Download`, and `Android/data`. The management UI therefore tells users to push content
  into a folder of its own, e.g. `adb push SoundFonts /sdcard/Music/SoundFonts`, and to link
  that folder. uapmd's JSFX window already gives this advice.
- **Index.** The platform's own SQLite (`SQLiteOpenHelper`, not Room) holds packs, entries,
  files, kind metadata and a revision counter. Embedding apps therefore pay no extra
  dependency or annotation processing for it. Rescans happen on user action, when the host starts, and on `call("rescan")`. The
  index bumps the revision and calls `notifyChange` only when the content actually differs.
- **File delivery.** The folder provider implements §3.4: regular-file FDs pass through, and
  pipes are copied to the host's own cache.
- **Kind indexers** are pure Kotlin:
  - SFZ: lists entries by pattern.
  - SF2: parses the RIFF `pdta/phdr` chunk into preset metadata. This replaces usf2plugin's
    `readPresetHeaders` over SAF.
  - JSFX: produces candidates only. uapmd lets ysfx's parser decide, and the host should not
    second-guess it.
- **UI-free host API.** host-core exposes folder management without any UI toolkit:
  - list folders, with their kinds, availability and counts;
  - add a folder for kinds chosen first, imported or in place, through a plain (non-Compose)
    activity in host-core that only runs the system folder picker and returns the result;
  - switch a folder between imported and in place;
  - remove a kind from a folder, or the folder, which deletes its copy and releases its grant;
  - set a folder's kinds;
  - rescan.

  The API is available from Kotlin and through the C API (§4). An app with its own UI
  toolkit builds its folder panel on it: uapmd's ImGui `JsfxResourcesWindow`, or
  usf2plugin's JUCE `DirectorySettingsPanel`.
- **Management UI (optional).** `soundpack-host-ui` is a Compose screen built on the same
  API. It has one screen per kind with folders, packs and an entry tree, plus "Add folder",
  "Rescan" and pack details (licence/readme). It is shipped inside
  `SoundpackFoldersActivity`, which an app or its plugin UI opens: JUCE apps through JNI,
  SDL apps through `SDL_GetAndroidActivity()`.

  The hub uses it too. Apps that already depend on Compose (sfizz) take it as is; apps that
  don't (uapmd) can avoid Compose entirely.

**Where setup happens (D5).** In the plugin UI, because DAWs only open the plugin UI. A
plugin app's own activities are not changed for soundpack setup. The plugin UI opens the
folder screen (`SoundpackFoldersActivity`; "SFZ folders" in sfizz), which links folders
through the system picker. The Soundpack Manager needs no setup step in clients (§3.6).

S5 measured that, on Android 14+, Android blocks activity starts from a plugin UI embedded
in another app's window, unless the host binds with `BIND_ALLOW_ACTIVITY_STARTS`. The
design does not ask hosts for that privilege (§8).

**Embedded mode** (an app opts in per kind):

- The app declares
  `<provider android:name="…host.LocalSfzFolderProvider" android:authorities="${applicationId}.soundpack.local.sfz" android:exported="false"/>`.
- The client finds it by convention from `context.packageName`, so no discovery is needed.
- Folders, grants and the index belong to that app alone.

**Hub mode adds:**

- exported providers that every app reads (§3.6);
- a browser over everything installed, per kind.

**When an app has local folders and the hub is installed:**

- The catalog shows both. Packs with the same deterministic id (§3.7) are shown once, and
  the local provider wins, so no IPC is needed.
- The management screen labels each folder with where it is managed: "this app" or
  "Soundpack Manager".
- It can offer **"Share with Soundpack Manager"** for a local folder: pass the tree URI to the
  hub with read, persistable **and prefix** grant flags.
  **S6 confirmed:** the hub persists the delegated grant, and keeps access after the app
  releases its own. The prefix flag is required: without it, the hub holds a grant on the
  tree URI alone, and ExternalStorageProvider refuses to list the tree. Moving a folder never
  changes its pack id, so saved states keep resolving.


4. Repository layout and technology
-----------------------------------

```text
soundpack-provider-for-android/
  gradle/libs.versions.toml     aligned with AAP (compileSdk 37, minSdk 29, AGP 9.3, Kotlin 2.2)
  soundpack-contract/           kinds, URIs, columns, identity parser, path normaliser, SF2 PHDR reader
  soundpack-provider-sdk/       SoundpackProvider base (URI layout, rules) + AssetPackProvider
                                and its res/xml metadata parser
  soundpack-client/             Kotlin client: discovery, catalog, sessions, prefetch, materialize
  soundpack-native/             prefab AAR: the C API (soundpack.h, soundpack_host.h) over JNI, mmap helper
  soundpack-ui-compose/         SoundpackPicker (generalised SfzTree)
  soundpack-host-core/          folder sources, SAF walker, pipe→cache, index DB, kind indexers,
                                folder provider base + Local<Kind>FolderProvider classes,
                                UI-free host API + folder-picker activity (no Compose)
  soundpack-host-ui/            optional Compose folder management screens + SoundpackFoldersActivity
  soundpack-testkit/            provider conformance tests runnable against any authority
  app/                          Soundpack Manager hub: host core + host UI + exported providers,
                                folder sharing
  samples/                      sample-sfz-pack (ported sample-sfz-provider), sample-sf2-pack, sample-jsfx-pack,
                                embedded-client (embedded folder mode, Phase 2 gate tests),
                                native-client (the C API from C++, Phase 5 tests)
  tools/                        make-sample-sf2.py (generates the sample SoundFonts)
  docs/
```

- **Language.** Everything Android-facing is Kotlin. The only native code is the C ABI shim
  plus the mmap helper. C rather than C++ because sfizz, JUCE apps and uapmd all link
  `c++_shared` with different toolchains; a C ABI stays out of that.
- **Native packaging.** The native module is shipped as a prefab AAR, which AAP projects
  already consume (`buildFeatures { prefab = true }`). It is built with `ANDROID_STL=none`.
  It is a module of its own, `soundpack-native`, because its folder functions need host-core,
  which the client must not depend on.
- **Pack declaration** follows AAP's `aap_metadata.xml` convention. A `<meta-data>` on the
  `<provider>` points at `@xml/soundpack_<kind>`:

  ```xml
  <provider android:name="org.androidaudioplugin.soundpack.sdk.SfzAssetPackProvider"
            android:authorities="${applicationId}.soundpack.sfz" android:exported="true">
      <intent-filter><action android:name="org.androidaudioplugin.soundpack.sfz.V1" /></intent-filter>
      <meta-data android:name="org.androidaudioplugin.soundpack#Packs" android:resource="@xml/soundpack_sfz" />
  </provider>
  ```

  ```xml
  <packs>
    <pack id="org.studiorack.vpo3" label="Virtual Playing Orchestra 3" revision="3" assetRoot="vpo3">
      <entries pattern="Strings/*.sfz" group="Strings" scopes="Strings libs" />
      <entry path="…" label="…" />
    </pack>
  </packs>
  ```

  Packs that compute their lists in code, as vpo3 does today, subclass and override
  `packs()`. The `noCompress` requirement stays.
- **C API** (`soundpack.h`, `soundpack_host.h`):

  ```c
  int  soundpack_init(JNIEnv*, jobject context);                // once, from a thread with a JNIEnv
  const char* soundpack_last_error(void);                       // the last failure on this thread
  int  soundpack_format_identity(const char* kind, const char* pack_id, const char* entry_path,
                                 char* out, size_t out_length);

  // every entry of a kind, from every source; accessors per index, then free
  soundpack_entry_list* soundpack_list_entries(const char* kind);
  size_t      soundpack_entry_list_count(const soundpack_entry_list*);
  const char* soundpack_entry_identity(const soundpack_entry_list*, size_t index);
  //          ..._pack_label, _path, _label, _group, _metadata (JSON, e.g. SF2 presets)
  void        soundpack_entry_list_free(soundpack_entry_list*);

  soundpack_session* soundpack_open(const char* identity);      // blocking IPC
  const char* soundpack_session_entry(const soundpack_session*);
  size_t      soundpack_session_file_count(const soundpack_session*);
  const char* soundpack_session_file_path(const soundpack_session*, size_t index);
  int  soundpack_session_prefetch(soundpack_session*, const char* const* paths, size_t count);
  int  soundpack_session_open_fd(soundpack_session*, const char* path, int* fd, int64_t* offset, int64_t* length);
  int  soundpack_session_map(soundpack_session*, const char* path, soundpack_mapping* out);
  void soundpack_mapping_release(soundpack_mapping*);
  void soundpack_session_close(soundpack_session*);
  int  soundpack_materialize(const char* kind, const char* pack_id, const char* dest_dir);

  // soundpack_host.h: UI-free host API (embedded local folders, §3.9)
  soundpack_folder_list* soundpack_host_list_folders(void);
  //          soundpack_folder_count, _id, _display_name, _tree_uri, _kinds, _available,
  //          _file_count, _error, _imported, soundpack_folder_list_free
  int  soundpack_host_add_folder(const char* tree_uri, const char* kinds, char* id_out, size_t id_len);
  int  soundpack_host_import_folder(const char* tree_uri, const char* kinds, char* id_out, size_t id_len);
  int  soundpack_host_set_imported(const char* folder_id, int imported);
  int  soundpack_host_remove_folder(const char* folder_id);
  int  soundpack_host_set_kinds(const char* folder_id, const char* kinds);    // "sf2,sfz"
  int  soundpack_host_rescan(const char* folder_id /* NULL: all */);
  ```

  The C functions call into the Kotlin client, so identity parsing, access state and
  discovery live in one place. JUCE apps pass `juce::getEnv()`/`getAppContext()`; SDL apps
  pass `SDL_GetAndroidJNIEnv()`/`SDL_GetAndroidActivity()`. `soundpack_init()` loads the
  Kotlin side through the context's class loader, so later calls work from threads the engine
  created. Strings cross JNI as UTF-8 byte arrays, because JNI's own strings are "modified
  UTF-8".

  Changes from the first sketch, made in Phase 5:
  - Errors go to `soundpack_last_error()`, per thread, instead of a buffer in every call.
  - Entries can be listed, with their metadata, so usf2plugin lists SF2 presets without
    opening any SF2.
  - `soundpack_host_add_folder` links a tree URI that the app's own picker returned, instead
    of running a picker. JUCE and SDL apps already have one, and the plugin UI keeps its own
    folder button (D5).
  - `soundpack_session_prefetch` exposes `PackSession.prefetch()` (Phase 4).
- **Names (D3).** Everything that can never change goes under
  `org.androidaudioplugin.soundpack`: actions, `<meta-data>` names, the hub's applicationId
  and permission, and the Maven group (already published). Authority suffixes follow the
  `.soundpack.<kind>` convention. The identity scheme is `commons-soundpack://` (§3.7).
- **Dependencies (D3).** No module depends on aap-core. uapmd already does, so that is not the
  reason; the reasons are that non-AAP apps should be able to use these libraries, and that
  aap-core or aap-lv2 should be able to use soundpacks later without a dependency cycle.
  The limits per module:

  | Module | May depend on |
  | --- | --- |
  | contract | Kotlin stdlib only, so `uapmd-kmp` can later use it on desktop to parse identities. For now it is built as an Android library with no `android.*` imports |
  | provider-sdk | contract |
  | client | contract, kotlinx-coroutines (`Flow`) |
  | native | client, and host-core for the folder functions; C only, no C++ runtime |
  | host-core | provider-sdk (its folder providers extend `SoundpackProvider`) and the platform's SQLite; no client, no Compose, no Room, no coroutines |
  | host-ui | host-core, Compose Material3, activity-compose, coroutines |
  | ui-compose | client, Compose Material3, coroutines |
  | testkit | contract; used only from device tests |


5. Migrating the consumers
--------------------------

| Consumer | What changes |
| --- | --- |
| **aap-lv2-sfizz** | Delete `SfzResourceClient`, `SfzFolders`, `SfzFoldersActivity`, `SfzTree` and the `:sfz-provider` / `:sample-sfz-provider` modules. Declare `LocalSfzFolderProvider`, so local folders keep working without the hub. The existing `sfz-folders` trees are carried over on first launch, reusing their grants (§3.7). `SfzResourceViewFactory`, the plugin UI, uses `SoundpackPicker(kind = SFZ)` and keeps its "SFZ folders" button, which now opens `SoundpackFoldersActivity` (D5). `SfizzActivity` is not changed. A `PackSession` adapts directly to the existing `AudioPluginLV2ResourceBridge.Snapshot` (entry, names, lazy opener), so `AudioPluginLV2Resources_jni.cpp` and the sfizz patch are unchanged at first. Later the native mapping moves onto `soundpack-native`, and every pack goes through the lazy (ABI v2) path. `BundledSfzResourceService` becomes an SDK `<provider>`, or an in-process asset source if the piano should stop being advertised to other apps. Old `aap-sfz://` states restore as unavailable (§3.7). |
| **aap-sfz-vpo3, aap-sfz-salamander-drumkit** | Remove the vendored `sfz-provider/`. Depend on `soundpack-provider-sdk`, and declare the provider and XML (vpo3 keeps its code-generated list; `resourcePaths` becomes `scopes`). This is a clean break: release them together with the new sfizz, and say in the release notes that all three must be updated, because old and new versions cannot see each other. |
| **aap-juce-usf2plugin** | Rewrite the Android hunks of `aap-juce-support.patch`. Declare `LocalSf2FolderProvider` and link `soundpack-native`. The library lists SF2s through `soundpack_list_entries("sf2")`, so every source appears: its own linked folders, pack apps, and the Manager's shared folders. `scanDocumentTree`/`findDocument`/`readPresetHeaders` over SAF go away; presets come from entry metadata. `SoundFontFile::loadData` maps the file instead of copying it. `sf2Absolute` stores the identity. Existing tree URLs in the `sf2Folders` setting are carried over to the local provider once, reusing their grants, and states that name a document in such a tree resolve through the linked folder. On Android, `DirectorySettingsPanel` keeps its JUCE look and its folder picker in the plugin UI (D5), and links the picked tree through the UI-free host API (§3.9). Inside a host, Android blocks the picker on 14+ (S5). Desktop is unaffected. |
| **uapmd** | Declare `LocalJsfxFolderProvider` and add an Android-only JSFX source. `AppModel::syncJsfxRegisteredFolders` calls `soundpack_materialize("jsfx", pack, jsfxFolderMirrorRoot()/<name>)` for each JSFX pack in the catalog: local folders, plus the hub's when connected. Registered folders from `jsfxRegisteredFolders()` are carried over to the local provider once, reusing their grants. On Android, the registered-folder part of `JsfxResourcesWindow` stays in ImGui but runs on the UI-free host API through the C API, so uapmd takes on no Compose dependency. `IDocumentProvider` stays for project and file documents. Desktop and wasm are unaffected. |
| **Candidates later** | aap-juce-ysfx (JSFX), aap-juce-tone3000/byod (IR/NAM kinds), dexed (cartridges). |


6. Phases and gates
-------------------

**Phase 0: platform spikes.** Throwaway APKs, run on API 30+ devices: one physical arm64
device and one emulator. They are in `spikes/`, with the runbook and results table in
`docs/spikes.md`.

- S1: `<queries><intent>` plus `queryIntentContentProviders` sees a third-party provider's
  intent-filter.
- S2: a persistable prefix grant from a custom provider:
  - can be taken in an activity;
  - can be used from the `AudioPluginService` process;
  - survives a reboot and a hub upgrade;
  - stops working once the hub revokes it.
- S3: a cross-process `openAssetFileDescriptor` of an APK asset keeps a non-zero `startOffset`
  and `declaredLength`, and the client can `mmap` it.
- S4: a hub re-exporting an ExternalStorageProvider document hands the client an `S_ISREG`
  FD. Measure cold- and warm-process open latency.
- S5: the consent activity and `SoundpackFoldersActivity` can be launched from a plugin UI
  hosted by `AudioPluginViewService`.
- S6 (non-blocking): an app can pass its persisted SAF tree grant to the hub, and the hub can
  persist it ("Share with Soundpack Manager", §3.9).

Gate: each spike either passes, or its fallback (§3.6, §3.8, §3.9) is chosen and written
into the contract.

Status: **done** (2026-10-15) on a Galaxy Tab A11+ (Android 16) and an Android 15
emulator. S1–S4 and S6 pass. S5 found that, on Android 14+, a plugin UI shown in another
app's host cannot start activities unless the host lends a privilege, which the design does
not ask for (D5, §8).

**Phase 1: contract, provider SDK, client, samples.**

- Kotlin client: discovery, catalog Flow, PackSession, materialize.
- Testkit conformance suite.
- Sample packs for all three kinds.
- Port vpo3 and salamander to the SDK (no legacy adapter).

Gate: conformance passes for all sample packs and for vpo3. A test client opens the
include and sample of `sample-sfz-pack` across APKs, and enumerates VPO3 in a single cursor
query.

Status (2026-10-15): **in progress.** Done in this repository:

- `soundpack-contract`, with 17 JVM unit tests: kinds, routes, identities, path rules, globs
  and the SF2 preset reader.
- `soundpack-provider-sdk`: the `SoundpackProvider` base enforces the read-only rules,
  revision checks, read scopes, dot-file hiding and known lengths. `AssetPackProvider` adds
  XML or code declarations, glob entries, and SF2 preset metadata.
- `soundpack-client`: discovery, `catalog()` and `catalogFlow()`, identities with revision
  policies, `PackSession`, `Materializer`, and `Sf2Metadata`.
- `soundpack-testkit`: `ProviderConformance`.
- The three sample packs.
- `SamplePacksTest` (6 device tests) passes on both test devices. It covers conformance for
  every sample provider, the SFZ include and sample opened across APKs, SF2 presets from
  metadata, incremental materialize, the revision policies, and the catalog flow.

vpo3 and salamander are ported in their own repositories (Phase 4 has the details). On the
Galaxy Tab (2026-10-16), vpo3 conforms and lists its 454 entries in one query, which completes
this gate.

**Phase 2: host core and embedded mode.** No hub yet.

- Folder sources per kind, the index, the pipe-to-cache path and the folder provider base.
- `Local<Kind>FolderProvider` classes, the management UI and `SoundpackFoldersActivity`.
- The SF2 PHDR indexer.

Gate: with no hub installed, a test app that declares `LocalSfzFolderProvider` can:

- register a folder;
- list it in the picker;
- open its files as `S_ISREG` FDs.

The local provider passes the testkit. A folder with tens of thousands of files is rescanned
once, not on every open.

Status (2026-10-15): **gate passed** on the Galaxy Tab A11+ (Android 16). Built:

- **`soundpack-host-core`** (no Compose, no Room, no coroutines):
  - `FolderRegistry`, the UI-free host API: list, detect kinds, add, set kinds, remove,
    rescan, observe, and `pickFolderIntent` (kind detection was replaced by D6 in Phase 7);
  - the platform-SQLite `FolderIndex`, and the SAF `FolderScanner` (bounded walk, dot-files
    skipped, SF2 presets read at scan time, extensionless JSFX candidates confirmed by a
    `desc:` line);
  - the `FolderProvider` base with pipe-to-cache, and `Local{Sfz,Sf2,Jsfx}FolderProvider`,
    which refuse to start if exported;
  - `SoundpackFolderPickerActivity`, a plain activity that links in one step.
- **`soundpack-host-ui`**: `SoundpackFoldersScreen` and `SoundpackFoldersActivity`, with the
  D2 kind dialog (detected kinds pre-checked, a warning when nothing of the kind is found;
  replaced by D6 in Phase 7).
- **`soundpack-ui-compose`**: `SoundpackPicker`, a tree of every readable pack, live.
- **`samples/embedded-client`** declares local SFZ and SF2 providers. Its
  `EmbeddedFoldersTest` drives the system picker with UiAutomator, and both tests pass:
  - linking, kind detection, hidden-file exclusion, regular descriptors with known lengths,
    conformance of both local authorities, SF2 presets, revision movement with the identity
    still resolving, and the picker listing the folder;
  - a 20,000-file folder linked in about 16 s (picker included), after which 200 opens and
    10 catalog queries walk nothing; about 19 ms per open.

The Compose screen was also checked by hand on the tablet: add through the system picker,
the kind dialog, the folder card, the picker, then remove.

Decisions made while building it:

- **Live packs do not pin revisions.** A file request for a live (folder) pack is served
  whatever revision it names, because a rescan must not break a client that is still
  loading. Immutable packs still do. The base provider and the test kit implement this.
- **Folders without entries are not packs.** A folder with no entries of a kind is not
  listed as a pack of that kind; the folder UI shows it with a warning instead.
- **The startup rescan skips folders already scanned in this process.**
- **`soundpack.json` portable ids are not implemented yet** (§3.7).

**Phase 3: hub app.**

- The host core behind exported providers.
- Merging local and hub folders with de-duplication; "Share with Soundpack Manager".

Gate (as changed by D5, 2026-10-16; the first version gated per-app consent):

- a test client reads hub folders with no connect step, for every kind;
- a folder registered both locally and in the hub appears once;
- uninstalling the hub leaves local folders working.

Status (2026-10-15): **gate passed** on the Galaxy Tab A11+ (Android 16), with per-app
consent. D5 (2026-10-16) removed consent; the changed gate is in "Phase 4 results" below.
Built:

- **`app/`, the Soundpack Manager** (`org.androidaudioplugin.soundpack.manager`). It has
  `Sfz/Sf2/JsfxFolderProvider`: exported with no permission and discoverable by action. Its
  screens:
  - `ShareFolderActivity`: takes a folder an app shares, after the user confirms kinds;
  - `MainActivity`: Folders (the host-ui screen, for all kinds) and Browse
    (`SoundpackPicker` over everything installed).
- **Client**: `SoundpackClient.hubEndpoint()`; the hub is otherwise read like any pack.
  `SoundpackPicker` has `refreshKey` to reload the catalog.
- **Host core**: `FolderRegistry.shareWithHubIntent()`, and a "Share with Soundpack Manager"
  button on folder cards. A local folder provider refuses to run if exported. Hub constants
  are in `SoundpackContract.Hub`.
- **`HubGateTest`** (in `samples/embedded-client`), through the real hub screens and the
  system picker:
  - the hub answers for SFZ and SF2 with no connect step;
  - a folder shared with the hub appears once, with the local provider first;
  - the hub's SFZ and SF2 providers conform;
  - a saved identity falls back to the hub once the local folder is unlinked;
  - the opt-in test (`-e allowHubUninstall true`): with the hub uninstalled, local folders
    still list, open and conform.

Decisions made while building it:

- **The shared folder goes in ClipData.** The share intent carries the tree URI in its
  ClipData rather than its data, so the hub's intent filter matches without a MIME type.
  The grant flags cover ClipData, with PREFIX as S6 requires.

**Phase 4: sfizz migration**, released together with vpo3 and salamander (D4). The
`sfz-folders` trees carry over. Folder management stays in the plugin UI (D5).

Gate:

- sfizz's existing `SfzResourceClientTest` and `StatePersistenceTest` cases pass on the new
  client, with and without the hub;
- `commons-soundpack://` states round-trip across a process restart;
- an `aap-sfz://` state from 0.6.2 restores as "unavailable" without crashing;
- previously registered folders appear without re-picking;
- an external AAP host can select and restore instruments, and the plugin UI it shows starts
  no activities.

Status (2026-10-16): **gate passed on the Galaxy Tab A11+ (Android 16), except the external
host check, which is blocked (see below).** The libraries are published to Maven
Local as `org.androidaudioplugin.soundpack:*:0.1.0-dev`, which the three repositories build
against. In CI, aap-core's reusable `build-sfz-bundle-app.yml` checks out this repository
and publishes the libraries into Maven Local before building any aap-sfz-* app. sfizz instead
has this repository as the `external/soundpack-provider-for-android` submodule, like its
aap-lv2 and aap-core dependencies, and aap-lv2's reusable `build-dist-app.yml` publishes it
into Maven Local next to them, when a repository has that submodule. This lasts until the
libraries are published (Phase 7).

- **aap-sfz-salamander-drumkit** (2.0.0) and **aap-sfz-vpo3** (2.0.0): the vendored
  `sfz-provider/` and the old services are gone. Each declares an `SfzAssetPackProvider` with
  an XML pack (`org.androidaudioplugin.sfz.salamander-drumkit`, 9 entries;
  `org.studiorack.vpo3`, 454 entries with per-family `scopes`). Each has a `ProviderTest`
  that runs the conformance suite and counts the entries in one query.
- **aap-lv2-sfizz**:
  - The `sfz-provider/` and `sample-sfz-provider/` modules are gone, and so are
    `SfzResourceClient`, `SfzFolders`, `SfzFoldersActivity`, `SfzTree` and
    `BundledSfzResourceService`.
  - The bundled piano is an `SfzAssetPackProvider`
    (`io.github.sfzinstruments.splendid-grand-piano`). The app's own folders use
    `LocalSfzFolderProvider`.
  - `SoundpackResources` implements the native bridge's `Provider` with `SoundpackClient`,
    and opens each file only when sfizz asks for it. The native bridge is unchanged.
  - The plugin UI uses `SoundpackPicker`, and keeps its "SFZ folders" button, which opens
    `SoundpackFoldersActivity` (D5). `SfizzActivity` is unchanged.
  - `SfzSetupInitializer` moves the `sfz-folders` trees into `FolderRegistry` once.
  - Device tests:
    - `SoundpackResourcesTest` replaces `SfzResourceClientTest`. It runs the bundled piano's
      conformance, opens it for the bridge, and rejects an `aap-sfz://` identity.
    - `StatePersistenceTest` finds Salamander through the client. It checks that the state
      holds the `commons-soundpack://` identity, and restores it off and on the main thread.
      It also restores a state whose identity is a 0.6 `aap-sfz://` one, and a state saved in
      another process (two instrumentation runs, `-e stateRestart save|restore`).
    - `SfzFolderMigrationTest` links a folder the way 0.6 did (system picker, the app's grant,
      the `sfz-folders` list) and checks that it carries over.
  - No new permissions in the merged manifest.

Results on the Galaxy Tab, with the ports installed over sfizz 0.6.2, vpo3 1.0.0 and
salamander 1.0.0:

- Salamander and vpo3 `ProviderTest`s pass.
- The sfizz suite (12 tests) passes. Before D5 it ran with the hub not connected and with it
  connected; after D5, again against the hub with no connect step. The hub was never
  uninstalled for these runs.
- Saved selections: Salamander's `commons-soundpack://` state restores in a fresh instance
  and in a new process, with the same 535 regions and samples. A 0.6 `aap-sfz://` state
  restores with no instrument and keeps the selection.
- Migration with real data: sfizz 0.6.2 had `Download/SFZ` linked. On first start it was
  linked again without picking, with all 1,199 SFZ entries, and the old list was removed.
- After D5 (2026-10-16): the client suite (6 tests) passes with the hub installed and
  reports no failure for it; `HubGateTest` passes (the opt-in uninstall case was not run).
- The plugin UI's "SFZ folders" opens `SoundpackFoldersActivity` when sfizz's own app shows
  the plugin UI (`BAL_ALLOW_VISIBLE_WINDOW`), listing the migrated folder. In the AAP host
  validator, Android blocks it ("Background activity launch blocked"), as S5 measured.
- With aap-lv2 at `main` (below), sfizz runs in the validator: the instance starts, and its
  plugin UI loads `ALL.sfz` from the migrated folder (532 regions).
- Load time of that kit, from a folder shared in the Soundpack Manager: 14.3 s at first,
  because sfizz opens its 532 samples one at a time at about 23 ms each. Opening them on 8
  threads cost 6.2 ms each. `SfzReferences` (contract) lists the files an `.sfz` names, as
  sfizz resolves them; `PackSession.prefetch()` (client) opens them ahead of the engine, 8 at a
  time with at most 64 waiting, and closes what is not taken within 10 s.
  `SoundpackResources` uses both, and the load takes 3.5 s. The scan found all 532 samples,
  and nothing prefetched stayed open.
- `SoundOutputTest` plays a note and measures the outputs: the default piano, the bundled
  piano through its soundpack identity, and Salamander's kick all make sound.

Problems found that are not caused by the migration:

- **No sound in hosts: fixed.** sfizz's metadata declared the extensions BOM `0.12.1`, which
  includes the buses extension, while sfizz still built against aap-lv2 0.6.2 and aap-core
  0.11.2, which have none. A 0.12 host (the validator) called the buses extension right
  after creating an instance, and sfizz's service crashed with a null function call in
  `aap::LocalPluginInstance::controlExtension`. String Machine (aap-lv2 0.6.2, BOM `0.12.0`)
  ran fine in the same host. Fix: `external/aap-lv2` moves to aap-lv2 `main` (`8f34325`,
  with aap-core `6572b1d9`). sfizz's `gradle/libs.versions.toml` is a symlink into it, so the
  build now uses aap-lv2 0.7.0 and aap-core 0.12.0.
- `StatePersistenceTest` failed after `PluginTest` in the same process, with or without the
  migration. `AudioPluginServiceHelper.getServiceInstance(pluginId)` outside an instance scope
  returns the first service bound for the plugin, which is another test's. The test now calls
  the per-instance lookup reflectively; aap-core would need a public
  `getServiceInstance(pluginId, instanceId)` to avoid that.
- Samsung's restriction manager refused to start vpo3's process for provider queries
  ("Restricted by policy: 8"), from sfizz and from the shell alike, so VPO3 was missing from
  the catalog. vpo3 was in standby bucket 45 (rare); Salamander, in bucket 10 (active), was
  allowed. After vpo3 was opened once from the launcher, it moved to bucket 10 and its
  provider answered again. See §8, "Pack apps nobody opens".

**Phase 5: native C API and usf2plugin.**

Gate: without the hub, usf2plugin lists SF2s from local folders, with presets, without
opening any SF2 during a scan. Loading maps the file instead of copying it. State
round-trips. Existing `sf2Folders` tree URLs carry over.

Status (2026-10-16): **gate passed on the Galaxy Tab A11+ (Android 16).** The external host
check and usf2plugin's desktop build were not run.

- **soundpack-native**: the C API of §4, as a prefab AAR, published to Maven Local with the
  other libraries.
- **samples/native-client** uses it from C++ built with `c++_shared`. Its `NativeApiTest`
  (4 tests) passes:
  - It lists the sample SF2 pack's entries, with presets in their metadata.
  - It opens an identity, prefetches its files, maps the entry (`RIFF…sfbk`), and gets a
    descriptor with an offset into the pack APK.
  - A bad identity, and a tree URI the app holds no grant for, fail with a message in
    `soundpack_last_error()`.
  - It links a folder picked in the system picker, lists and rescans it, finds an entry by
    `soundpack_format_identity`, and unlinks the folder.
- **aap-juce-usf2plugin**:
  - `aap-juce-support.patch` adds `src/android/Soundpacks.{h,cpp}`, a C++ wrapper over the C
    API. It rewrites the Android parts of `AppSettings`, `SoundFontLibrary`,
    `PluginProcessor`, `DirectoryBar` and `DirectorySettingsPanel`, under `JUCE_ANDROID`.
    The SAF tree walker and the preset reading over SAF are gone.
  - The app declares `LocalSf2FolderProvider` and depends on `soundpack-native`.
  - The directory panel adds folders with JUCE's folder picker, as before, then links the
    tree on a background thread. Refresh rescans the linked folders.
  - On start, the `content://` keys of `sf2Folders` are linked and removed from the setting.
  - Device test: `SoundpackStateTest` loads the sample pack's `Sines.sf2` by identity
    through `setState`, checks that the saved state names it, and restores it in a fresh
    instance.
- In CI, aap-juce-usf2plugin has this repository as the
  `external/soundpack-provider-for-android` submodule, like sfizz. aap-juce's reusable
  `build-dist-with-cmake.yml` publishes it into Maven Local when a port has that submodule.

Results on the Galaxy Tab:

- Migration with real data: the previous usf2plugin had `Download/SF2` linked. On first start
  it was linked again without picking (`saf-3d413620…`), and its 3 SoundFonts were indexed
  with their presets. A state saved with the old document URL of `MS_Basic.sf2` resolved to
  `commons-soundpack://sf2/saf-3d413620…/MS_Basic.sf2`.
- In usf2plugin's own app, the plugin UI lists 6 SoundFonts: the bundled GeneralUser GS, the
  2 of the sample pack and the 3 of the migrated folder. It lists 887 presets in all (e.g.
  MS_Basic 309, alex_gm 137), read from the index. Selecting MS_Basic loads it.
- The first build showed 0 presets for every soundpack entry. The wrapper read the preset
  array out of a parsed JSON value that had already been destroyed. Fixed in the patch.

**Phase 6: uapmd JSFX.**

Gate: materialize is incremental; a second sync of an unchanged pack writes nothing. Removed
files disappear from the catalog. Existing registered folders carry over. Desktop builds are
untouched.

**Phase 7: hardening and V1 freeze.**

- Measure FD counts, mapping sizes, first-load latency and underruns against sfizz's bundled
  piano (the gate 5 work sfizz deferred).
- Freeze the actions, URIs, columns and identity grammar.
- Publish the artifacts.
- Optional: a `DocumentsProvider` compatibility root, so SAF-only apps can pick hub
  content. Note that `DocumentsProvider.openAssetFile` is final and always reports offset 0
  with unknown length, so APK packs would have to be served as pipes or materialised copies.
  Folder packs would pass through unchanged.

Status (2026-10-16): **first-load latency measured**; the rest is not started.

sfizz's `LoadLatencyTest` (aap-lv2-sfizz, device test) times six loads of one instrument per
source, a second apart, each case in its own process. A load ends when sfizz has parsed the
instrument and preloaded its samples; a note right after it must sound within eight blocks. For the
pack app and the Manager, the first load also starts their process. Galaxy Tab A11+, warm page
cache (dropping it needs root):

| Instrument, source | First load | Later loads (median) |
| --- | --- | --- |
| Splendid Grand Piano (360 regions, 226 samples), file in app storage, as before soundpacks | 116 ms | 113 ms |
| The same piano, soundpack from sfizz's own APK | 197 ms | 150 ms |
| Salamander `ALL.sfz` (532 samples), soundpack from its pack app | 957 ms | 366 ms |
| The same kit from a folder shared in the Soundpack Manager (SAF) | 3.8 s | 3.7 s |

- A soundpack from an APK costs about 80 ms more than a file for the piano's first load, and
  about 40 ms more later.
- Starting a pack app's process, with the client's first use, adds about 600 ms to the first
  load.
- The folder is about ten times slower than the APK for the same kit. Opening 532 files through
  the Manager's provider and SAF dominates, even 8 at a time.
- **Fixed: every later load loaded the previous instrument again first.** The bridge's `load()`
  calls sfizz's `set_resource_host()`, which reloaded the current selection whenever one was
  set; it is there to complete a selection restored before a host existed. Each instrument
  switch in the plugin UI paid the previous instrument's load time too: later loads took
  278 ms, 684 ms and 8.0 s in the rows above. This came from the sfizz patch (`1b7c3f7`), not
  from the soundpack migration. `sfizz-android.patch` now marks such a selection as pending
  (`resource_pending`), and `set_resource_host()` loads only a pending one. sfizz's device
  tests pass, including the restore across a process restart; no test covers completing a
  pending selection.

Kind first, then the folder, and import or in place (D6), 2026-10-16:

- host-core: `FolderIndex` version 2 adds `imported` and `import_error`, migrated in place.
  `FolderImports` keeps each imported folder's copy. `FolderProvider` serves imported files
  from the copy, and answers file opens without the per-open entry statistics it used to
  compute. provider-sdk gained `hasEntry()` for that.
- host-ui, the Manager and the embedded sample list folders one kind at a time, with "Import
  a folder" and "Use a folder in place". The Manager's share screen takes the kinds from the
  app that shares, and asks whether to import.
- Device tests on the Pixel 11 Pro Fold (Android 17), while the Galaxy Tab was away:
  - the client suite (6) and the C API suite (4, now switching a folder to imported and back);
  - `EmbeddedFoldersTest` (3), with the new `importedFolderServesItsCopy`: a folder linked as
    SFZ only is not guessed into an SF2 pack; its files open from the copy; the copy keeps
    serving after the folder is deleted; and using it in place again deletes the copy;
  - `HubGateTest`, which now removes the shared folder from the Manager's SFZ and SF2 views.
- Load times on the Pixel. The kit was copied from the Salamander repository to
  `/sdcard/Music/SoundpackLatencyKit` and linked in sfizz through the picker
  (`salamanderFromLinkedFolder`, in place and with `-e linkMode imported`). The Pixel's own
  shared folder holds no Salamander kit, so the case through the Manager was skipped. Each
  case ran in its own process, with the screen on:

  | Instrument, source | First load | Later loads (median) | One open, alone / 8 at a time |
  | --- | --- | --- | --- |
  | Piano, file in app storage, as before soundpacks | 86 ms | 119 ms | — |
  | Piano, soundpack from sfizz's own APK | 185 ms | 463 ms | — |
  | Salamander `ALL.sfz`, from its pack app | 1051 ms | 673 ms | 3.64 / 0.56 ms |
  | The same kit, sfizz's own folder, in place | 610 ms | 1002 ms | 23.10 / 1.58 ms |
  | The same kit, sfizz's own folder, imported | 489 ms | 658 ms | 0.14 / 0.29 ms |

  Importing the kit (550 files, 261 MB) took 1.0 s.
- **Fixed while measuring: imported folders were slower than in place.** The first
  measurement gave a median of 1.85 s imported, against 1.25 s in place and 0.67 s from the
  APK. An imported file opened in 0.3 ms alone, but in 2.9 ms each 8 at a time: a local
  provider runs in sfizz's process, and every open made four SQLite queries, which all 8
  prefetch threads then waited on. `FolderProvider` now keeps each folder's files and
  entries in memory. A write from the same process invalidates them at once
  (`FolderIndex.writes`), and a write from another process within 250 ms
  (`FolderIndex.stamp`, read without a cursor). An imported folder now loads like the APK.
- Load times on the Galaxy Tab, after that fix, with the kit's folder from the Manager's
  shared SFZ folder (`-e kitPath Download/SFZ/endolith/salamander-drumkit/1.0.0`):

  | Instrument, source | First load | Later loads (median) | One open, alone / 8 at a time |
  | --- | --- | --- | --- |
  | Piano, file in app storage, as before soundpacks | 113 ms | 114 ms | — |
  | Piano, soundpack from sfizz's own APK | 189 ms | 150 ms | — |
  | Salamander `ALL.sfz`, from its pack app | 930 ms | 363 ms | 1.28 / 0.27 ms |
  | The same kit, folder shared in the Manager, in place | 2.59 s | 1.86 s | 29.04 / 2.47 ms |
  | The same kit, sfizz's own folder, in place | 1.52 s | 1.52 s | 20.56 / 2.17 ms |
  | The same kit, sfizz's own folder, imported | 438 ms | 318 ms | 0.33 / 0.11 ms |
  | The same kit, folder imported in the Manager | 1020 ms | 407 ms | 1.59 / 0.34 ms |

  Importing the kit (550 files, 261 MB) took 3.4 s in sfizz and 4.3 s in the Manager. The
  Manager case (`salamanderFromManagerImported`) shares the folder from sfizz with "Import a
  copy", unlinks it in sfizz, and removes it from the Manager afterwards; its first load
  starts the Manager's process. An imported folder now loads faster than the pack app's APK
  in the same app, and about as fast from the Manager, and the Manager's folder in place dropped from 3.7 s to 1.86 s, since
  the Manager's provider no longer queries SQLite on every open either. The Tab shows no
  slowdown in later loads.
- **Not a soundpack issue: on the Pixel, later loads in a process are slower than the
  first, for every source.** Once sfizz closed its sessions (below), the Pixel still showed
  it: piano from a file 92 ms, then 154 ms; piano from sfizz's APK 353 ms, then 446 ms; the
  kit's folder in place 650 ms, then 987 ms; imported, 407 ms, then 692 ms. The plain file
  path, which uses no soundpack code, slows the same way, and the Galaxy Tab shows none of it.
  The likely cause is the Pixel's CPU boost after a process starts wearing off.
- **Fixed: sfizz never closed the sessions of the instruments it replaced.** Its native bridge
  dropped a lazy snapshot without calling `close()`, so each session's prefetch threads, and
  any files they had opened that sfizz never took, stayed until the prefetcher idled 10 s
  later. `~Pack` now closes the snapshot. sfizz's device tests pass.


7. Testing
----------

- **JVM unit tests:**
  - path normalisation and escape rejection;
  - identity parse and format (`commons-soundpack://`);
  - tree building, ported from `SfzTree`;
  - SF2 PHDR parsing on truncated and corrupt input;
  - XML metadata parsing.
- **`soundpack-testkit`** is an instrumented suite parameterised by authority:
  - paging across `CursorWindow` boundaries;
  - revision mismatch errors;
  - traversal attempts;
  - AFD validity (regular file, bounds);
  - concurrent opens;
  - a provider process killed mid-session.
- **Native tests** run on a device or emulator through CTest: mapping alignment on 4 KiB and
  16 KiB page sizes, offset and length overflow, and release of mappings. This also gives
  sfizz's `ResourceIndexTest` a real build target.
- **Cross-app scenarios** on CI with the emulator. Install the sample packs and a test
  client, then run:
  - the cases with no hub installed;
  - with the hub: reading it with no connect step, local/hub de-duplication, and
    uninstalling the hub or a provider.


8. Risks
--------

- **Open latency (measured in S4, Android 16 tablet).**
  - Starting a cold hub process and listing a folder: ~180 ms.
  - Each file open through the hub: ~15 ms (a direct SAF open costs ~11 ms).
  - The folder provider must resolve paths from its index; querying SAF on each open
    brought it to 27 ms.

  sfizz opens samples one at a time, so an instrument with N samples spends about N × 15 ms
  opening them. Opening them in parallel on the client side (`PackSession.prefetch()`, Phase
  4 results) took a 532-sample kit from 14.3 s to 3.5 s. An "open many" call would save the
  remaining per-call overhead if that is still too slow. Local providers inside the app avoid the extra hop: measured
  in Phase 2, an open through the in-app folder provider costs about 19 ms, of which about
  11 ms is the SAF open itself.
- **Live folders.** The bytes behind a folder pack can change under a mapping. The current
  advice stays: do not modify samples while they are in use, and reselect to reload.
- **Grant limits.** Android caps the number of persisted URI grants per app (historically
  128, raised on newer releases). An app holds one grant per local folder, which is the
  same as today's per-app folder lists.
- **Folders in two places.** Embedded mode means the same folder can be registered in
  several apps and scanned by each. That is the price of not requiring the hub. De-duplication
  by deterministic pack id keeps the catalog clean. The "managed in" label and "Share with
  Soundpack Manager" give users a way to consolidate, but they are never forced to.
- **Embedded plugin UIs cannot start activities (S5).** On Android 14+, a plugin UI shown
  in another app's host counts as a background app, and starting any activity from it is
  blocked (`BAL_BLOCK`). Only the host could lend that right, to every plugin it binds, and
  we do not ask for it. Setup still lives in the plugin UI (D5): its folder button opens the
  folder screen, which works where the plugin UI may start activities, and is blocked in an
  external host on Android 14+.
- **Pack apps nobody opens.** A pack app is usually installed and never opened, so its
  standby bucket drifts to "rare". On a Galaxy Tab (Android 16), Samsung's restriction
  manager then refuses to start its process for provider queries, and its packs vanish from
  every catalog until the user opens the app once (Phase 4 results). Clients cannot lift
  this. Phase 7: report such an endpoint as a failure that names the app and says to open it
  (it is discovered, but its provider cannot be acquired). Pack apps should keep a launcher
  screen, as vpo3 does, so that users have a way to wake them. Check whether other vendors do the same.


9. Decisions
------------

- **D1. Hub-required or embedded folder mode.** *Resolved 2026-10-15:* the hub is never
  required for local folders. Every client can embed the host core with a private
  `…soundpack.local.<kind>` provider (§3.9). The hub is an optional shared registry, and
  folders keep the same pack id wherever they are served (§3.7).

- **D2. How a folder relates to kinds.** *Resolved 2026-10-15; superseded 2026-10-16 by D6,
  except for the read scope and the share wording.* As resolved then; this only matters
  in the hub and in apps that embed more than one kind; in a single-kind app, every linked
  folder is that kind.
  - **The folder is the unit.** It is linked once and holds a set of kinds, so one scan
    serves all of them. The SF2 preset parser runs only where SF2 is enabled.
  - **Detected defaults plus confirmation.** When a folder is added, a name-only scan shows
    counts such as "120 `.sfz`, 3 `.sf2`", and kinds with entries are pre-checked. A kind
    detected later is only *suggested*: no change that lets more apps read a folder happens
    without a yes.
  - **A warning in single-kind apps.** If the folder has no entries of the app's kind
    ("No `.sfz` files found"), the wrong level of folder was probably linked.
  - **Per-kind read scope** (§3.2). SFZ and JSFX serve the whole tree minus dot-files. SFZ
    gets no extension allowlist, because samples, includes, tunings and images can have any
    name, and filtering would break unusual packs. SF2 serves only the `.sf2`/`.sf3` entry
    files, which are still browsed by their folder hierarchy.
  - **Share wording.** The hub's share screen says that every soundpack app can read the
    folder, as the kinds the user confirms. For SFZ and JSFX that is every file in it.
  *Alternative rejected:* sharing every folder automatically as every kind it contains. That
  silently widens access whenever a file is added, and pays parsing costs nobody asked for.

- **D3. Names and dependencies.** *Resolved 2026-10-15:*
  - **Names.** The Android wiring that can never change (actions, `<meta-data>` names, the
    hub's applicationId, the Maven group) goes under
    `org.androidaudioplugin.soundpack`. The identity scheme is `commons-soundpack://`: it
    marks the identity as meant for common use, independent of AAP or Android, and the
    `commons-` prefix keeps it distinct (§3.7).
  - **Dependencies.** There is no aap-core dependency, and each module stays within its
    dependency limit (§4).
  - **UI-free host API.** host-core offers folder management without any UI toolkit, from
    Kotlin and C, so ImGui and JUCE apps keep their own UI and Compose stays optional (§3.9).
- **D4. Legacy `SfzResourceService.V1`.** *Resolved 2026-10-15:* a clean break.
  - No legacy adapter in the SDK, and no translation of `aap-sfz://` or `aap-sfz-folder://`
    identities. Those states restore as "unavailable".
  - sfizz, vpo3 and salamander are released together, with release notes saying all three
    must be updated.
  - Folder registrations still carry over, because they are not URIs.
- **D5. Where setup happens, and access to the hub.** *Resolved 2026-10-16:*
  - **Setup is handled in the plugin UI**, because DAWs only open the plugin UI. A plugin
    app's own activities are never changed for soundpack setup. The plugin UI keeps its
    folder screen ("SFZ folders" in sfizz), which links folders through the system picker.
  - **No per-app consent.** The hub's providers are exported with no permission, like a pack
    app's. Sharing a folder in the Soundpack Manager publishes it to every soundpack app, and
    the Manager is simply another source for clients.
  - This removes the consent screen, "Connected apps", `SoundpackClient.hubAccess()` and its
    connect calls, `SoundpackConnectActivity`, and the picker's connect button and setup
    hint. It also undoes the Phase 4 move of setup into `SfizzActivity`.
- **D6. Kind first, then the folder; import or in place.** *Resolved 2026-10-16;* it
  replaces D2's detection and the rule that the host never copies content.
  - **The user chooses the kind, then picks the folder.** The folder is linked for that kind
    only. Nothing is detected or pre-checked from its content, and no folder is walked before
    it is linked. A folder can be linked again for another kind, as a deliberate step. File
    types do not decide what a folder is for: the audio-sample kind planned for aap-core's
    plugin manager (aap-core#165) accepts any audio file, so detection would offer every SFZ
    folder as samples too, and index thousands of WAV and FLAC files nobody asked for.
  - **Import or in place, per folder** (§3.9). Import copies what the folder's kinds serve
    into the app's storage: it loads like an installed pack and keeps working without the
    folder, but takes storage. In place keeps the files where they are, and every file open
    goes through SAF and FUSE, about 6 ms each even 8 at a time; a 532-sample kit loaded about
    ten times slower than the same kit from an APK (Phase 7).
  - D2's per-kind read scope and share wording stay.
  - The UIs list folders one kind at a time, with "Import a folder" and "Use a folder in
    place". `FolderRegistry` has `add(tree, kinds, import)`, `setImported` and `removeKind`;
    `detectKinds` and the kinds dialog are gone. The C API adds
    `soundpack_host_import_folder`, `soundpack_host_set_imported` and
    `soundpack_folder_imported`; the existing functions are unchanged.

- **D7. Resource types.** *Resolved 2026-10-16* (aap-core#165): there is no generic "WAV"
  type. Instead every kind is a resource type with a label, a description and file types
  (wildcards):
  - **Defaults:** SFZ, SF2, JSFX and "AAP Sample" (`hostsample`), a built-in kind with
    its own providers, so aap-core's plugin manager can embed it as sfizz embeds SFZ.
  - **User-defined types:** users add types in the Soundpack Manager's Types tab, for an app
    with a format of its own. A type's id comes from its label when it is created and never
    changes, since saved identities carry it. Its folders are served whole, and its file types
    pick the entries, as an app may need what is next to them (samples next to an
    instrument); users get no option for this. The Manager serves all added types through one
    types provider (§3.2).
  - **Editing:** default types are fully editable (label, description, file types) and can be
    reset, but not deleted; they keep their read scope, which their players depend on.
    Changing a type's file types rescans the folders linked as it. Deleting an added type
    stops using its folders as it.
  - **Storage:** the definitions are kept in the app's settings, through host-core's
    `ResourceTypes`. Apps that never edit them get the defaults.
  - **Presentation:** the folder screen shows the type's label, and its description when
    there is one, next to the link-or-import text, which is now the same for every type.

All decisions are settled.

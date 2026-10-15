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
MTP, or use a file manager. The host never uploads or copies content. It only **links** a
folder that is already on the device, either in the hub or as a local folder inside an app.

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
once, and grant it per app and per kind.

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
  obtain an activity result.
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
   AssetPackProvider (public read)       exported, consent-gated
            │                                        │
            └─── ContentResolver: query / openAssetFileDescriptor / call ───┐
                                                                            ▼
 Consumer app (aap-lv2-sfizz plugin process, usf2plugin, uapmd …)
   soundpack-client (Kotlin): discovery, access state, catalog Flow, PackSession, materialize
   soundpack-native (C ABI over JNI): open session, open fd, mmap helper
   soundpack-ui-compose: picker tree, "manage folders" / "connect" affordances
   embedded host core (opt-in per kind):
     <provider …soundpack.local.sfz>  not exported; this app's own folders, no hub needed
```

From a client's point of view, every source is **just another provider** of a kind:

- **Pack APKs** are public, discovered by action, and read directly. They work without the hub.
- **The app's own local provider** serves the folders the user registered in this app. It is
  not exported, so no other app can see it, and it needs no consent.
- **The hub's provider**, when installed and connected, serves the shared registry.

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

Because grants are made per authority, an app that the user connected for SFZ cannot read
the user's JSFX or SF2 folders. The separation the user asked for is enforced by the
permission model, not only by convention.

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

### 3.6 Access and consent

- **Pack APK providers** are exported with no permission, because their content is public.
- **An app's local providers** are not exported. Only the app itself reads them; the user
  consented by picking the folder in that app.
- **The hub's providers** are exported with a hub-defined `signature` read permission and
  `grantUriPermissions="true"`. Nobody can read them directly; access is granted per client
  and per kind.

The hub flow (an app that never connects to the hub still works, using its local folders and
pack APKs):

1. The client calls `SoundpackClient.hubAccess(kind)` and gets one of `Granted`,
   `NeedsConsent(intent)` or `HubNotInstalled`. `HubNotInstalled` is not an error; the
   management screen just offers a link to get the hub.
2. On `NeedsConsent`, the client launches the hub's `ConnectActivity`, which shows something
   like "AAP Sfizz wants to read your SFZ soundpacks".
3. The hub returns `content://<hub>.soundpack.sfz/` with `READ | PERSISTABLE | PREFIX`.
4. The client calls `takePersistableUriPermission` on exactly that root URI.
5. The hub's "Connected apps" screen lists each grant and revokes it with
   `revokeUriPermission`.

Calls from AAP plugin UIs come from a service-hosted view. They go through a small
trampoline activity in the client library, the same pattern as `SfzFoldersActivity`.

**S2 confirmed:** a persistable prefix grant on the root of a custom (non-`DocumentsProvider`)
provider works:

- it can be taken in an activity;
- it is usable from every process of the client app;
- it survives a hub upgrade, a client restart and a reboot;
- the hub can revoke it for one client or for all.

The allowlist fallback is not needed.

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

- **Sources** are linked folders only: a persisted SAF tree grant, with the files left where
  the user put them. Each folder is assigned to one or more kinds; see D2 for how. An
  embedding app only offers the kinds it declared a local provider for.
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
  - add a folder, through a plain (non-Compose) activity in host-core that only runs the
    system folder picker and returns the result;
  - remove a folder, which releases its grant;
  - set a folder's kinds;
  - rescan.

  The API is available from Kotlin and through the C API (§4). An app with its own UI
  toolkit builds its folder panel on it: uapmd's ImGui `JsfxResourcesWindow`, or
  usf2plugin's JUCE `DirectorySettingsPanel`.
- **Management UI (optional).** `soundpack-host-ui` is a Compose screen built on the same
  API. It has one screen per kind with folders, packs and an entry tree, plus "Add folder",
  "Rescan" and pack details (licence/readme). It is shipped inside
  `SoundpackFoldersActivity`, which any app can launch:
  - AAP plugin UIs hosted by a service launch it the way sfizz launches
    `SfzFoldersActivity` today;
  - JUCE apps launch it through JNI;
  - SDL apps launch it through `SDL_GetAndroidActivity()`.

  The hub uses it too. Apps that already depend on Compose (sfizz) take it as is; apps that
  don't (uapmd) can avoid Compose entirely.

**Embedded mode** (an app opts in per kind):

- The app declares
  `<provider android:name="…host.LocalSfzFolderProvider" android:authorities="${applicationId}.soundpack.local.sfz" android:exported="false"/>`.
- The client finds it by convention from `context.packageName`, so no discovery is needed.
- Folders, grants and the index belong to that app alone.

**Hub mode adds:**

- exported providers and the consent flow (§3.6);
- a "Connected apps" screen;
- one tab per kind for everything the hub serves.

**When an app has local folders and the hub is connected:**

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
  soundpack-client/             Kotlin client + prefab native module (C ABI, JNI, mmap helper)
  soundpack-ui-compose/         SoundpackPicker (generalised SfzTree), access/connect affordances
  soundpack-host-core/          folder sources, SAF walker, pipe→cache, index DB, kind indexers,
                                folder provider base + Local<Kind>FolderProvider classes,
                                UI-free host API + folder-picker activity (no Compose)
  soundpack-host-ui/            optional Compose folder management screens + SoundpackFoldersActivity
  soundpack-testkit/            provider conformance tests runnable against any authority
  app/                          Soundpack Manager hub: host core + host UI + exported providers,
                                consent, connected apps
  samples/                      sample-sfz-pack (ported sample-sfz-provider), sample-sf2-pack, sample-jsfx-pack
  tools/                        make-sample-sf2.py (generates the sample SoundFonts)
  docs/
```

- **Language.** Everything Android-facing is Kotlin. The only native code is the C ABI shim
  plus the mmap helper. C rather than C++ because sfizz, JUCE apps and uapmd all link
  `c++_shared` with different toolchains; a C ABI stays out of that.
- **Native packaging.** The native module is shipped as a prefab AAR, which AAP projects
  already consume (`buildFeatures { prefab = true }`).
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
- **C API sketch** (`soundpack.h`):

  ```c
  int  soundpack_init(JavaVM*, jobject context);               // once, on a Java thread
  soundpack_session* soundpack_open(const char* identity, char* err, size_t errlen); // blocking IPC
  const char* soundpack_session_entry(const soundpack_session*);
  size_t      soundpack_session_file_count(const soundpack_session*);
  const char* soundpack_session_file_path(const soundpack_session*, size_t index);
  int  soundpack_session_open_fd(soundpack_session*, const char* path, int* fd, int64_t* offset, int64_t* length);
  int  soundpack_session_map(soundpack_session*, const char* path, soundpack_mapping* out);
  void soundpack_mapping_release(soundpack_mapping*);
  void soundpack_session_close(soundpack_session*);
  int  soundpack_materialize(const char* kind, const char* pack_id, const char* dest_dir, char* err, size_t errlen);

  // UI-free host API (embedded local folders, §3.9)
  int  soundpack_host_list_folders(const char* kind, soundpack_folder_list** out);
  void soundpack_host_add_folder(const char* kind, soundpack_folder_callback cb, void* user); // runs the picker
  int  soundpack_host_remove_folder(const char* folder_id);
  int  soundpack_host_set_kinds(const char* folder_id, const char* const* kinds, size_t count);
  int  soundpack_host_rescan(const char* folder_id /* NULL: all */);
  ```

  The C functions call into the Kotlin client, so identity parsing, access state and
  discovery live in one place. JUCE apps pass `juce::getEnv()`/`getAppContext()`; SDL apps
  pass `SDL_GetAndroidJNIEnv()`/`SDL_GetAndroidActivity()`.
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
  | client | contract, kotlinx-coroutines (`Flow`); the native part needs only the NDK |
  | host-core | client, provider-sdk (its folder providers extend `SoundpackProvider`) and the platform's SQLite; no Compose, no Room |
  | testkit | contract; used only from device tests |
  | ui-compose, host-ui | Compose Material3; optional for apps |


5. Migrating the consumers
--------------------------

| Consumer | What changes |
| --- | --- |
| **aap-lv2-sfizz** | Delete `SfzResourceClient`, `SfzFolders`, `SfzFoldersActivity`, `SfzTree` and the `:sfz-provider` / `:sample-sfz-provider` modules. Declare `LocalSfzFolderProvider`, so local folders keep working without the hub. The existing `sfz-folders` trees are carried over on first launch, reusing their grants (§3.7). `SfzResourceViewFactory` uses `SoundpackPicker(kind = SFZ)` plus a "Manage folders" button that opens `SoundpackFoldersActivity`. A `PackSession` adapts directly to the existing `AudioPluginLV2ResourceBridge.Snapshot` (entry, names, lazy opener), so `AudioPluginLV2Resources_jni.cpp` and the sfizz patch are unchanged at first. Later the native mapping moves onto `soundpack-native`, and every pack goes through the lazy (ABI v2) path. `BundledSfzResourceService` becomes an SDK `<provider>`, or an in-process asset source if the piano should stop being advertised to other apps. Old `aap-sfz://` states restore as unavailable (§3.7). |
| **aap-sfz-vpo3, aap-sfz-salamander-drumkit** | Remove the vendored `sfz-provider/`. Depend on `soundpack-provider-sdk`, and declare the provider and XML (vpo3 keeps its code-generated list; `resourcePaths` becomes `scopes`). This is a clean break: release them together with the new sfizz, and say in the release notes that all three must be updated, because old and new versions cannot see each other. |
| **aap-juce-usf2plugin** | Rewrite the Android hunks of `aap-juce-support.patch`. Declare `LocalSf2FolderProvider`. `SoundFontDirectory`'s tree-URL branch becomes "soundpack packs of kind SF2", taken from the local provider plus the hub when connected. `scanDocumentTree`/`findDocument`/`readPresetHeaders` over SAF become index queries, with presets taken from entry metadata. `SoundFontFile::loadData` maps the file instead of copying it. `sf2Absolute` stores the identity. Existing tree URLs in the `sf2Folders` setting are carried over to the local provider once, reusing their grants. On Android, the folder part of `DirectorySettingsPanel` keeps its JUCE look but runs on the UI-free host API through the C API (§3.9). It could instead open `SoundpackFoldersActivity`, since aap-juce apps already ship Compose. Desktop is unaffected. |
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
emulator. Every spike passes except S5, which needs aap-core to bind plugin UIs with
`BIND_ALLOW_ACTIVITY_STARTS` (§8). That change is outside this repository and not yet made.

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

Not done: porting vpo3 and salamander (their own repositories), and therefore the VPO3 part
of the gate.

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

**Phase 3: hub app.**

- The host core behind exported providers.
- Consent and "Connected apps".
- Merging local and hub folders with de-duplication; "Share with Soundpack Manager".

Gate:

- a test client sees hub folders only for the kinds it was granted;
- revoking a grant is honoured;
- a folder registered both locally and in the hub appears once;
- uninstalling the hub leaves local folders working.

**Phase 4: sfizz migration**, released together with vpo3 and salamander (D4). The
`sfz-folders` trees carry over.

Gate:

- sfizz's existing `SfzResourceClientTest` and `StatePersistenceTest` cases pass on the new
  client, with and without the hub;
- `commons-soundpack://` states round-trip across a process restart;
- an `aap-sfz://` state from 0.6.2 restores as "unavailable" without crashing;
- previously registered folders appear without re-picking;
- an external AAP host can select and restore instruments.

**Phase 5: native C API and usf2plugin.**

Gate: without the hub, usf2plugin lists SF2s from local folders, with presets, without
opening any SF2 during a scan. Loading maps the file instead of copying it. State
round-trips. Existing `sf2Folders` tree URLs carry over.

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
  - with the hub: grant, revoke, local/hub de-duplication, and uninstalling the hub or a
    provider.


8. Risks
--------

- **Open latency (measured in S4, Android 16 tablet).**
  - Starting a cold hub process and listing a folder: ~180 ms.
  - Each file open through the hub: ~15 ms (a direct SAF open costs ~11 ms).
  - The folder provider must resolve paths from its index; querying SAF on each open
    brought it to 27 ms.

  sfizz opens samples one at a time, so an instrument with N samples spends about N × 15 ms
  opening them. If that is too slow at VPO3 scale, add an "open many" call, or open in
  parallel on the client side. Local providers inside the app avoid the extra hop entirely.
- **Live folders.** The bytes behind a folder pack can change under a mapping. The current
  advice stays: do not modify samples while they are in use, and reselect to reload.
- **Grant limits.** Android caps the number of persisted URI grants per app (historically
  128, raised on newer releases). An app holds one grant per hub kind plus one per local
  folder, which is the same as today's per-app folder lists.
- **Folders in two places.** Embedded mode means the same folder can be registered in
  several apps and scanned by each. That is the price of not requiring the hub. De-duplication
  by deterministic pack id keeps the catalog clean. The "managed in" label and "Share with
  Soundpack Manager" give users a way to consolidate, but they are never forced to.
- **Background activity launch from service-hosted plugin UIs (S5: confirmed problem).** On
  Android 16, a plugin UI hosted by `AudioPluginViewService` cannot start *any* activity when
  the host binds with aap-core's current `BIND_AUTO_CREATE`; the start is `BAL_BLOCK`. This
  affects:
  - the folder screen (`SoundpackFoldersActivity`);
  - the hub's consent screen;
  - sfizz's existing `SfzFoldersActivity`, whenever an external host shows the plugin UI.

  Binding with `BIND_AUTO_CREATE | BIND_ALLOW_ACTIVITY_STARTS` makes the same starts
  allowed (`BAL_ALLOW_BOUND_BY_FOREGROUND`). This needs a change in aap-core's
  `AudioPluginSurfaceControlClient.bindPluginViewService()`. It must land before sfizz
  migrates (Phase 4).


9. Decisions
------------

- **D1. Hub-required or embedded folder mode.** *Resolved 2026-10-15:* the hub is never
  required for local folders. Every client can embed the host core with a private
  `…soundpack.local.<kind>` provider (§3.9). The hub is an optional shared registry, and
  folders keep the same pack id wherever they are served (§3.7).

- **D2. How a folder relates to kinds.** *Resolved 2026-10-15* as follows. This only matters
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
  - **Consent wording.** The hub's consent screen says that apps connected for SFZ or JSFX
    can read every file in folders shared as that kind.
  *Alternative rejected:* sharing every folder automatically as every kind it contains. That
  silently widens access whenever a file is added, and pays parsing costs nobody asked for.

- **D3. Names and dependencies.** *Resolved 2026-10-15:*
  - **Names.** The Android wiring that can never change (actions, `<meta-data>` names, the
    hub's applicationId and permission, the Maven group) goes under
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

All decisions are settled. The next step is Phase 0.

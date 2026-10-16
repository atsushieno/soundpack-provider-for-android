# soundpack-provider-for-android

A Soundpack Manager app and libraries that give Android audio apps uniform, read-only
access to sound content outside the app: SFZ, SF2 and JSFX packs published by other apps,
and folders the user put on the device.

Status:

- **Phase 0** (platform spikes): done.
- **Phase 1** (contract, provider SDK, client, test kit): done and passing on devices,
  including the ported vpo3 and salamander.
- **Phase 2** (host core, embedded folder mode): its gate passes, so apps can link their own
  folders.
- **Phase 3** (the Soundpack Manager hub): its gate passes. Folders shared in the hub reach
  every soundpack app, with no connect step (D5).
- **Phase 4** (aap-lv2-sfizz on these libraries, with vpo3 and salamander): its gate passes
  on the Galaxy Tab, except the external host check. That one waits for sfizz to move to
  aap-core 0.12, because hosts built on 0.12 crash sfizz 0.11.2's plugin service.

- [docs/PLAN.md](docs/PLAN.md): survey of the existing implementations, the architecture,
  the decisions taken, and the phases with their status.
- [docs/spikes.md](docs/spikes.md): the Phase 0 spike runbook and results.

## Modules

| Module | What it is |
| --- | --- |
| `soundpack-contract` | Kinds, content URI layout, columns, `commons-soundpack://` identities, path rules, SF2 preset reader, SFZ reference scanner. Pure Kotlin. |
| `soundpack-provider-sdk` | `SoundpackProvider` base class, and `AssetPackProvider` for packs shipped as APK assets (`SfzAssetPackProvider`, `Sf2AssetPackProvider`, `JsfxAssetPackProvider`). |
| `soundpack-client` | `SoundpackClient`: discovery, catalog (and a live `Flow`), opening saved identities, prefetching an instrument's files (`PackSession.prefetch`), `materialize` for path-only engines. |
| `soundpack-native` | The C API for native engines (`soundpack.h`, `soundpack_host.h`), as a prefab package: entries with their metadata, sessions with file descriptors, mappings and prefetch, `materialize`, and linked folders. Pure C, so it needs no C++ runtime. |
| `soundpack-testkit` | `ProviderConformance`, to check any provider from a device test. |
| `soundpack-host-core` | Linked folders without any UI toolkit: `FolderRegistry`, the folder index, `Local{Sfz,Sf2,Jsfx}FolderProvider`, and a picker activity that links in one step. |
| `soundpack-host-ui` | Optional Compose folder management: `SoundpackFoldersScreen` / `SoundpackFoldersActivity`. |
| `soundpack-ui-compose` | `SoundpackPicker`: every readable pack's entries as a tree. |
| `app` | The Soundpack Manager (`org.androidaudioplugin.soundpack.manager`): folders shared with every soundpack app. |
| `samples/` | One small pack APK per kind; `embedded-client`, an app with its own folders that also reads the hub; and `native-client`, which uses the C API from C++. |
| `spikes/` | Phase 0 throwaway APKs. |

## Build and test

```sh
./gradlew assembleDebug :soundpack-contract:testDebugUnitTest
```

The device tests read the sample packs through Binder, so install those first:

```sh
./gradlew :samples:sample-sfz-pack:installDebug :samples:sample-sf2-pack:installDebug :samples:sample-jsfx-pack:installDebug
```

```sh
./gradlew :soundpack-client:connectedDebugAndroidTest
```

The embedded-folder tests drive the system folder picker (in English) and create a
20,000-file fixture in `/sdcard/Music/SoundpackLargeTest`. The fixture is kept between runs,
because creating it takes minutes:

```sh
./gradlew :samples:embedded-client:connectedDebugAndroidTest
```

```sh
adb shell rm -rf /sdcard/Music/SoundpackLargeTest
```

The hub tests (`HubGateTest`) need the hub installed first. They change the hub's state while
they run: they share a test folder and remove it again. They also read every folder already
shared as SFZ or SF2 there, to run the conformance checks. Prefer a device whose hub holds no personal folders. One of them uninstalls the hub,
so it runs only when asked:

```sh
./gradlew :app:installDebug
```

The C API tests need the sample SF2 pack. One of them links `/sdcard/Music/SoundpackNativeTest`
through the system folder picker (in English):

```sh
./gradlew :samples:native-client:connectedDebugAndroidTest
```

```sh
./gradlew :samples:embedded-client:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.allowHubUninstall=true
```

## Reading the Soundpack Manager's folders

The hub's providers are discovered and read like any pack: there is no connect step. A folder
the user shares in the Soundpack Manager reaches every soundpack app, as the kinds the user
confirms there.

## Where setup happens

In the plugin UI, because DAWs only open the plugin UI. Do not add soundpack setup to the
plugin app's own activities. The plugin UI opens the folder screen (`SoundpackFoldersActivity`)
with `FLAG_ACTIVITY_NEW_TASK`, as AAP Sfizz's "SFZ folders" button does.

On Android 14+, Android blocks activity starts from a plugin UI embedded in another app's
window (docs/spikes.md, S5). These libraries do not ask hosts to lift that.

## Letting users link their own folders

Depend on `soundpack-host-core` (and `soundpack-host-ui` for the Compose screen). Declare a
private provider for each kind you use; it must not be exported:

```xml
<provider
    android:name="org.androidaudioplugin.soundpack.host.LocalSfzFolderProvider"
    android:authorities="${applicationId}.soundpack.local.sfz"
    android:exported="false" />
```

`SoundpackClient` then finds these folders next to installed packs. To add folders, use one
of:

- `FolderRegistry.pickFolderIntent()`, which picks and links in one step without Compose;
- `SoundpackFoldersActivity`, the Compose folder screen;
- `FolderRegistry` directly, from your own UI.

The first two start activities; see "Where setup happens".

## From native code

Depend on `soundpack-native`, enable `buildFeatures { prefab = true }`, and link it in CMake:

```cmake
find_package(soundpack-native REQUIRED CONFIG)
target_link_libraries(your-engine PRIVATE soundpack-native::soundpack)
```

Call `soundpack_init()` once, from a thread that has a `JNIEnv` (JUCE apps pass
`juce::getEnv()` and `juce::getAppContext()`). Every call may block on IPC, so never call one
from an audio thread. `soundpack_list_entries("sf2")` lists entries from every source, with the
metadata the index holds (SF2 presets, for example), so listing opens no files.
`soundpack_open()` takes a saved identity, and `soundpack_session_map()` maps one of its files
read-only. For linked folders, `soundpack_host.h` links a tree URI that the app already holds
a grant for, so the app keeps its own folder picker. `samples/native-client` uses all of it.

## Publishing a pack

Depend on `soundpack-provider-sdk`, keep the pack's assets uncompressed, and declare a
provider for its kind. For example, an SFZ pack (see `samples/sample-sfz-pack`):

```xml
<provider
    android:name="org.androidaudioplugin.soundpack.sdk.SfzAssetPackProvider"
    android:authorities="${applicationId}.soundpack.sfz"
    android:exported="true">
    <intent-filter>
        <action android:name="org.androidaudioplugin.soundpack.sfz.V1" />
    </intent-filter>
    <meta-data
        android:name="org.androidaudioplugin.soundpack#Packs"
        android:resource="@xml/soundpack_sfz" />
</provider>
```

The contract is provisional until the V1 freeze (PLAN.md, Phase 7).

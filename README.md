# soundpack-provider-for-android

A Soundpack Manager app and libraries that give Android audio apps uniform, read-only
access to sound content outside the app: SFZ, SF2 and JSFX packs published by other apps,
and folders the user put on the device.

Status:

- **Phase 0** (platform spikes): done.
- **Phase 1** (contract, provider SDK, client, test kit): done and passing on devices,
  except that the existing pack apps are not ported yet.
- **Phase 2** (host core, embedded folder mode): its gate passes, so apps can link their own
  folders.
- **Phase 3** (the Soundpack Manager hub): its gate passes. Apps connect per kind, folders
  can be shared with the hub, and connections can be revoked.

- [docs/PLAN.md](docs/PLAN.md): survey of the existing implementations, the architecture,
  the decisions taken, and the phases with their status.
- [docs/spikes.md](docs/spikes.md): the Phase 0 spike runbook and results.

## Modules

| Module | What it is |
| --- | --- |
| `soundpack-contract` | Kinds, content URI layout, columns, `commons-soundpack://` identities, path rules, SF2 preset reader. Pure Kotlin. |
| `soundpack-provider-sdk` | `SoundpackProvider` base class, and `AssetPackProvider` for packs shipped as APK assets (`SfzAssetPackProvider`, `Sf2AssetPackProvider`, `JsfxAssetPackProvider`). |
| `soundpack-client` | `SoundpackClient`: discovery, catalog (and a live `Flow`), opening saved identities, `materialize` for path-only engines. |
| `soundpack-testkit` | `ProviderConformance`, to check any provider from a device test. |
| `soundpack-host-core` | Linked folders without any UI toolkit: `FolderRegistry`, the folder index, `Local{Sfz,Sf2,Jsfx}FolderProvider`, and a picker activity that links in one step. |
| `soundpack-host-ui` | Optional Compose folder management: `SoundpackFoldersScreen` / `SoundpackFoldersActivity`. |
| `soundpack-ui-compose` | `SoundpackPicker`: every readable pack's entries as a tree. |
| `app` | The Soundpack Manager (`org.androidaudioplugin.soundpack.manager`): folders shared with every app, behind per-kind consent. |
| `samples/` | One small pack APK per kind, and `embedded-client`, an app with its own folders that also connects to the hub. |
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
they run: they connect and disconnect the sample app, and share a test folder and remove it
again. They also read every folder already shared as SFZ there, to run the conformance
checks. Prefer a device whose hub holds no personal folders. One of them uninstalls the hub,
so it runs only when asked:

```sh
./gradlew :app:installDebug
```

```sh
./gradlew :samples:embedded-client:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.allowHubUninstall=true
```

## Reading the Soundpack Manager's folders

The hub's providers are discovered like any pack, but each kind needs the user's consent.
`SoundpackClient.hubAccess()` tells you whether you have it. When it returns
`NeedsConsent`, start `connectActivityIntent()`, which asks the hub and keeps the grant.
`SoundpackPicker` shows a connect button when you pass `onConnectHub`.

## Where setup happens

Connecting to the hub and linking folders open other screens: the hub's consent screen and
the system folder picker. Start them only from one of your app's own activities. A UI that
another app shows cannot start activities on Android 14+, and these libraries do not ask
hosts to change that. An AAP plugin UI embedded in a host is such a UI.

In those UIs, use `SoundpackPicker` to browse and select only. Leave `onConnectHub` null, and
pass a `setupHint` such as "Open AAP Sfizz to add folders". Setup is a one-time step that the
user does in the plugin app itself or in the Soundpack Manager.

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

The first two start activities, so they are for your app's own activities (see "Where setup
happens").

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

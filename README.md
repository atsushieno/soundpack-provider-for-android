# soundpack-provider-for-android

A Soundpack Manager app and libraries that give Android audio apps uniform, read-only
access to sound content outside the app: SFZ, SF2 and JSFX packs published by other apps,
and folders the user put on the device.

Status: Phase 0 (platform spikes) is done. Phase 1 is in progress: the contract, provider
SDK, client and test kit exist and pass on devices, but the existing pack apps are not ported
yet. There is no Soundpack Manager app yet.

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
| `samples/` | One small pack APK per kind. |
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

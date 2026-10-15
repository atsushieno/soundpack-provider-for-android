Phase 0 platform spikes: runbook
================================

These APKs check the platform behaviours that `docs/PLAN.md` marks **[spike]**, before any
contract is written down. They are throwaway code under `spikes/`; delete them once the
results are recorded below and folded into the plan.

| APK | Plays | Used by |
| --- | --- | --- |
| **Spike Client** (`…spike.client`) | a consumer app such as aap-lv2-sfizz; runs every check | S1–S6 |
| **Spike Hub** (`…spike.hub`) | the Soundpack Manager: consent-gated provider, linked folder, delegated grants | S2, S4, S6 |
| **Spike Pack** (`…spike.pack`) | a pack APK with a public provider; also the AAP *host* for S5 | S1, S3, S5 |
| **Spike Invisible** (`…spike.invisible`) | the negative control for S1 | S1 |

Every line the apps log also goes to logcat under the tag `SoundpackSpike`:

```bash
adb logcat -s SoundpackSpike
```


Build and install
-----------------

```bash
./gradlew assembleDebug
```

```bash
for apk in client hub pack invisible; do adb install -r spikes/$apk/build/outputs/apk/debug/$apk-debug.apk; done
```

Devices: Android 11 (API 30) or later; S1 and S5 mean nothing on older versions. Per the
plan, use one physical arm64 device and one x86_64 emulator. For S5, include at least one
Android 14+ device (background activity start rules) and one Android 15+ device (the
`InputTransferToken` path that aap-core takes).

All four APKs share the debug signing key, so a client that *requested* the hub's
`signature` permission would be granted it. The Spike Client deliberately does not request
it; access must come only from the URI grant.


Order matters
-------------

Run **S1 first**, on a fresh install of the client. Once the Spike Pack app interacts with
the client (S5 binds the client's service), Android makes the pack visible to the client
anyway, and S1 proves nothing. To rerun S1, uninstall and reinstall the client:

```bash
adb uninstall org.androidaudioplugin.soundpack.spike.client
```


S1: `<queries><intent>` makes provider intent-filters visible
-------------------------------------------------------------

In Spike Client, tap **S1**.

- **PASS:** `org.androidaudioplugin.soundpack.spike.pack` is listed for
  `…spike.sfz.V1`, the control action lists nothing, and the control package is not
  visible through `getPackageInfo`. The pack app's only component the client could match is
  its provider, so its intent-filter is what made it visible.
- **INCONCLUSIVE:** the control is visible too.
- **FAIL:** the pack is not listed. Fallback (PLAN §3.8): pack APKs also declare a marker
  `<service>` with the same action.

The hub may appear in the list too; that is expected, because its consent activity is
visible through a separate `<queries>` entry.


S2: persistable prefix grant from a custom provider
---------------------------------------------------

In Spike Client:

1. **S2a**: expect `SecurityException` (Permission Denial). The hub's provider is guarded
   by a signature permission.
2. **S2b**: the hub's consent screen opens; tap **Allow**. Expect
   `takePersistableUriPermission(content://…spike.hub.soundpack.sfz/): OK`, then a
   successful read of `/packs` and `hello.txt`.
3. **S2c** and **S2d**: the same read from the main process and from the `:plugin`
   process. Expect both to succeed, which shows that the grant belongs to the app (its UID)
   and not to one process.

Durability. After each of these, tap **S2e** and expect the grant still listed and the read
still working:

- reboot: `adb reboot`. On the Galaxy Tab A11+, this turned wireless debugging off, so it
  had to be re-enabled on the tablet before the run could continue;
- hub upgrade:

  ```bash
  ./gradlew :spikes:hub:assembleDebug -PspikeVersionCode=2
  ```

  ```bash
  adb install -r spikes/hub/build/outputs/apk/debug/hub-debug.apk
  ```

  Spike Hub then shows version `0.0.2`.

Revocation. In Spike Hub, tap **revoke the client's grant only**, then **S2e** in the client.
Expect the read to fail; also note whether the grant has gone from the persisted list.
Repeat **S2b**, then try **revoke all grants on the hub root** followed by **S2e**.

**FAIL** at any step means the fallback (PLAN §3.6): an allowlist of client UIDs and
signing-certificate digests, enforced inside the hub's provider.


S3: APK asset ranges cross processes and can be mapped
------------------------------------------------------

In Spike Client, tap **S3**. The pack serves a 3 MiB + 123 byte uncompressed asset through
`assets.openFd()`.

- **PASS:**
  - `startOffset > 0` and `declaredLength` equal the asset size;
  - the descriptor is a regular file;
  - mapping the range (`FileChannel.map`, i.e. `mmap(2)`) gives bytes whose SHA-256
    matches the pack's own.
- Record the offset. It is not page-aligned, which is exactly what sfizz's native mapping
  code has to handle.


S4: the hub passes through regular-file descriptors
---------------------------------------------------

1. Put a few files at the top level of a folder of their own. Android 11+ refuses the
   storage root and the root of `Download` in the folder picker. For example:

   ```bash
   adb shell mkdir -p /sdcard/Music/SpikeTest
   ```

   ```bash
   adb push some-sample.wav /sdcard/Music/SpikeTest/
   ```

2. In Spike Hub, tap **S4: link a folder** and choose that folder.
3. In Spike Client, make sure S2b has been done. For a cold measurement, run this just
   before the next step:

   ```bash
   adb shell am kill org.androidaudioplugin.soundpack.spike.hub
   ```

4. Tap **S4**.

**PASS:** every descriptor is `S_ISREG=true` and maps. Record:

- the first (cold) open time;
- the 20-open warm average and maximum;
- `declaredLength`. Expect `-1`: `DocumentsProvider.openAssetFile` reports an unknown
  length, so the real folder provider must fill it in from `st_size`, as sfizz's
  `SfzFolders` does today.


S5: activity starts from a service-hosted plugin UI
---------------------------------------------------

The Spike Pack app plays the AAP host. It binds the client's `SpikeViewService` the way
aap-core's `AudioPluginSurfaceControlClient` binds `AudioPluginViewService`
(`BIND_AUTO_CREATE`), and embeds the returned `SurfacePackage`.

1. Open **Spike Pack**, tap **S5: embed client UI (bind like aap-core)**. A light blue
   panel from the client appears at the bottom.
2. In that panel, tap **Open own activity**. **PASS:** the client's TrampolineActivity
   appears. If nothing appears, look for a blocked background activity start:

   ```bash
   adb logcat | grep -iE "background activity|BAL"
   ```

3. Tap **Open the hub's consent screen directly**. It should appear, though it cannot
   return a result.
4. From the TrampolineActivity, tap **Connect to the hub from here**. This is the real
   flow: consent, then a persisted grant and a successful read.
5. On Android 14+, repeat with **+ BIND_ALLOW_ACTIVITY_STARTS**. If step 2 fails with
   plain `BIND_AUTO_CREATE` but works with this flag, AAP hosts need to bind with it. That
   is an aap-core change, not a soundpack one.

In Spike Client, **S5: show what the embedded UI recorded** lists what happened.


S6: delegating a SAF folder grant to the hub
--------------------------------------------

Use a folder the hub has **not** linked in S4; otherwise the hub already holds a grant and
the test proves nothing.

1. In Spike Client: **S6a**, and pick the folder.
2. **S6b**: the client passes the tree URI to the hub with READ | PERSISTABLE | PREFIX.
   Without PREFIX, the hub can persist the grant but cannot list the tree. The result line
   comes from the hub. **PASS:** `takePersistableUriPermission: OK`, then
   `hub holds persisted grant: true` and the hub can list the tree.
3. **S6c**: the client releases its own grant.
4. **S6d**: the hub re-checks. **PASS:** it still holds the grant and can list the tree.
   Optionally reboot and repeat S6d. Spike Hub's **list the hub's persisted grants** shows
   the same from the hub's side.

**FAIL** means the fallback in PLAN §3.9: the hub opens the folder picker at that folder
(`EXTRA_INITIAL_URI`), and the user confirms.


Results
-------

Fill in one column per device, then update the [spike] markers in `docs/PLAN.md`.

| | Device 1 | Device 2 |
| --- | --- | --- |
| Model, Android version | Samsung Galaxy Tab A11+ (SM-X230), Android 16 (API 36), One UI 8.5, arm64-v8a, 4 KiB pages; run 2026-10-15 | x86_64 emulator: not run yet |
| S1 provider visibility | **PASS**: pack found only through its provider's intent-filter; control hidden from both `queryIntentContentProviders` and `getPackageInfo` | |
| S2 grant: take / :plugin / reboot / upgrade / revoke one / revoke all | **PASS**: before consent, Permission Denial. Grant taken with flags `0xc1`; reads OK from the main and `:plugin` processes. Survives a hub upgrade (versionCode 1 → 2) plus client force-stop. Both "revoke for client" and "revoke all" remove the persisted grant and deny access. **Survives a reboot**: same `persistedTime` afterwards, and reads OK from the main and `:plugin` processes | |
| S3 offset, length, mmap | **PASS**: `startOffset` 2,543,268 (not page-aligned: 3,748 into a page), `declaredLength` 3,145,851 = asset size, regular file, `st_size` 5,724,089 (the whole APK), SHA-256 of the mapped range matches | |
| S4 S_ISREG, cold ms, warm avg/max ms, declaredLength | **PASS**: all regular files, all mappable, `declaredLength` −1. Cold hub start + listing 174–193 ms. Through the hub, with an index: 15–18 ms first open, 14.3–22.7 ms warm average (max 31). Re-listing the folder on every open instead: 27 ms. Direct one-hop SAF open (S4b baseline): 10.7–12.4 ms | |
| S5 own activity / hub activity / real flow / with BIND_ALLOW_ACTIVITY_STARTS | **FAIL with `BIND_AUTO_CREATE`** (aap-core's current binding): both starts `BAL_BLOCK`, caller `BOUND_TOP` without a visible window. **PASS with `BIND_AUTO_CREATE \| BIND_ALLOW_ACTIVITY_STARTS`**: both starts `BAL_ALLOW_BOUND_BY_FOREGROUND`, and the full consent flow works from the opened activity | |
| S6 hub persists delegated grant / survives client release | **PASS only with PREFIX**: with READ \| PERSISTABLE, the hub persists the grant but ExternalStorageProvider denies listing the tree. With READ \| PERSISTABLE \| PREFIX, the hub persists it and lists the tree; after the client releases its own grant, the hub still lists it and the client loses access. **Survives a reboot**, as does the hub's own S4 folder grant | |

### What the results change

- **S1, S2, S3: confirmed.** No fallback is needed: provider discovery by
  `<queries><intent>`, consent through a persistable prefix grant on the authority root, and
  APK sub-ranges across processes all work as planned.
- **S4: confirmed, with two requirements.**
  - The folder provider must fill in `declaredLength` from `st_size`, because
    ExternalStorageProvider reports −1.
  - It must resolve paths from its index rather than querying SAF on each open: 15 ms
    against 27 ms.

  Going through the hub costs about 3–5 ms per open over a direct SAF open. sfizz opens
  samples lazily, so an instrument with N samples spends roughly N × 15 ms opening them.
  That is the "open many" risk in PLAN §8; it is now measured, not guessed.
- **S5: aap-core must change.** On Android 14+, a plugin UI hosted by
  `AudioPluginViewService` cannot start any activity unless the host binds with
  `BIND_ALLOW_ACTIVITY_STARTS`. That covers sfizz's existing `SfzFoldersActivity` as much as
  the planned `SoundpackFoldersActivity` or the hub's consent screen. The fix is in
  `AudioPluginSurfaceControlClient.bindPluginViewService()`, not in the soundpack libraries.
- **S6: works when the delegation includes `FLAG_GRANT_PREFIX_URI_PERMISSION`**, so
  "Share with Soundpack Manager" needs no fallback.


Clean up
--------

```bash
for p in client hub pack invisible; do adb uninstall org.androidaudioplugin.soundpack.spike.$p; done
```

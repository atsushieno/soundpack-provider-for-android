# soundpack-provider-for-android

A Soundpack Manager app and libraries that give Android audio apps uniform, read-only
access to sound content outside the app: SFZ, SF2 and JSFX packs published by other apps,
and folders the user put on the device.

Status: planning and platform spikes (Phase 0). Nothing here is usable yet.

- [docs/PLAN.md](docs/PLAN.md): survey of the existing implementations, the architecture,
  the decisions taken, and the phases.
- [docs/spikes.md](docs/spikes.md): how to build, install and run the Phase 0 spike APKs
  in `spikes/`, and where to record the results.

## Build

```sh
./gradlew assembleDebug
```

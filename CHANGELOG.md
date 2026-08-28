# Changelog

## 26.2-2.4.0

### Added

- Multi-loader project layout (`api`, `common`, `fabric`, `neoforge`) based on MultiLoader-Template 26.2
- Stable public api module (`com.modularmods.mcgltf.api`) for dependent mods
- NeoForge 26.2 support with client resource reload and shader-mod probing
- NeoForge client GameTest smoke harness (`runClientGameTest`)

### Changed

- **Breaking:** entry point is now `MCglTFApi.get()` instead of `MCglTF.getInstance()`
- **Breaking:** `GltfModelHandle.renderable()` replaces `GltfModelHandle.get()`
- **Breaking:** `IGltfModelReceiver` replaced by `GltfModelReceiver` in the api module
- Implementation types (`RenderedGltfModel`, `MCglTFImpl`, JglTF classes) are internal to `common`
- Release artifacts are split into api, Fabric, and NeoForge JARs

### Migration

Update [MCglTF-Example](https://github.com/ModularMods/MCglTF-Example) to depend on `mcgltf-api` at compile time and the loader-specific runtime JAR for your platform.

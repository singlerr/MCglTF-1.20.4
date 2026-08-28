# MCglTF for Minecraft 26.2

A client-side glTF/GLB/VRM model loader for Minecraft 26.2 on **Fabric** and **NeoForge**. Models are submitted through Minecraft's Blaze3D render pipeline, so vanilla rendering, Iris 1.11.x, entity passes, and first-person hand passes share the same state management instead of using raw OpenGL hooks.

[![](https://cf.way2muchnoise.eu/title/mcgltf.svg)](https://www.curseforge.com/minecraft/mc-mods/mcgltf) [![](https://cf.way2muchnoise.eu/versions/mcgltf.svg)](https://www.curseforge.com/minecraft/mc-mods/mcgltf) [![](https://cf.way2muchnoise.eu/mcgltf.svg)](https://www.curseforge.com/minecraft/mc-mods/mcgltf)

## Requirements

- Minecraft 26.2
- Java 25

**Fabric**

- Fabric Loader 0.19.3 or newer
- Fabric API 0.156.0+26.2

**NeoForge**

- NeoForge 26.2.0.1-beta or newer

## Project layout

This repository is a [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template) style Gradle project:

| Module | Output | Purpose |
|--------|--------|---------|
| `api` | `mcgltf-api-26.2-*.jar` | Stable public API for dependent mods (`modCompileOnly`) |
| `fabric` | `MCglTF-Fabric-26.2-*.jar` | Fabric runtime |
| `neoforge` | `MCglTF-NeoForge-26.2-*.jar` | NeoForge runtime |
| `common` | internal | Shared renderer implementation |

## Usage

Register once during client initialization. The returned handle always points at the model prepared by the latest resource reload:

```java
import com.modularmods.mcgltf.api.GltfModelHandle;
import com.modularmods.mcgltf.api.MCglTFApi;

GltfModelHandle handle = MCglTFApi.get().registerModel(
    Identifier.fromNamespaceAndPath("example", "models/avatar.vrm"));

if (handle.isReady()) {
    handle.renderable().submit(sceneIndex, poseStack, submitNodeCollector, packedLight, packedOverlay);
}
```

Call `handle.close()` when the registration is no longer needed. Do not cache `handle.renderable()` across resource reloads; keep the handle and read its current renderable when submitting.

Example mod: https://github.com/ModularMods/MCglTF-Example

### Dependent mod Gradle (Fabric)

```gradle
dependencies {
    modCompileOnly "com.modularmods.mcgltf:mcgltf-api:26.2-2.4.0"
    modRuntimeOnly "com.modularmods.mcgltf:MCglTF-Fabric:26.2-2.4.0"
}
```

For local development in this repo:

```gradle
dependencies {
    modCompileOnly project(":api")
    modRuntimeOnly project(":fabric")
}
```

### Migration from 26.2-Fabric-2.3.x

| Before | After |
|--------|-------|
| `MCglTF.getInstance()` | `MCglTFApi.get()` |
| `handle.get().submit(...)` | `handle.renderable().submit(...)` |
| `IGltfModelReceiver` | `GltfModelReceiver` (api module; simplified hook) |
| Direct `RenderedGltfModel` usage | Use `GltfRenderable` from the api module |

Implementation types (`RenderedGltfModel`, `ToonShader`, etc.) remain in the internal `common` module and are not part of the stable api contract.

## Features

- [x] glTF format (embedded resources or via `Identifier`)
- [x] GLB format
- [x] VRM containers based on glTF 2.0
- [x] UVs
- [x] Normals
- [x] Vertex colors
- [x] Base-color textures, sampler filtering/wrapping, and glTF alpha modes
- [x] Multiple texture-coordinate sets selected by the material
- [x] Skinning with up to eight joint influences
- [x] Runtime node transforms and morph targets
- [x] Zero-scale node culling
- [x] Opaque-first stable batching and cached CPU deformation
- [x] Vanilla and Iris-compatible first-/third-person submission
- [x] VRM 0.x MToon base/shade textures with per-material shift/toony ramp and rim
- [x] Angle-limited smooth MToon normals and standard-entity inverted-hull outlines
- [x] Optional ToonShader materials with LightMap ramps, face SDF, head axes, depth rim, material outlines, matcap/specular, emission, blush, and day/night ramps

MToon uses a dedicated managed Blaze3D entity pass when ShaderPacks are off: the VRM 0.x `VRM.materialProperties` base and shade textures are mixed at the material's `_ShadeShift`/`_ShadeToony` light boundary with a small parameter rim. The controls travel in the generated shade texture alpha, which the managed pass restores before writing opacity. When Iris has an active ShaderPack, ordinary MCglTF callers still use its standard entity pass and never enter the ToonShader renderer.

ToonShader remains an internal opt-in path. Use `GltfRenderable.MTOON_OVERLAY_REQUEST` when submitting through the public api; advanced ToonShader frame composition still lives in the internal renderer.

### Optional ToonShader sidecar

The sidecar is JSON version 2 (version 1 remains supported) and normally sits beside the model as `model.vrm.toon.json`. PNG paths are relative to that file and must stay in the same directory tree. Invalid material/node/primitive references, traversal, non-PNG textures, profiles over 1 MiB, or textures over 64 MiB fail model preparation instead of guessing.

```json
{
  "version": 2,
  "head": {"name": "Head", "forward": [0, 0, -1], "right": [1, 0, 0]},
  "lightDirectionMultiplier": [1, 0.55, 1],
  "baseColorScale": 0.65,
  "smoothNormals": "generate",
  "smoothNormalAngle": 180,
  "rampTexture": "avatar-ramp.png",
  "materials": [{
    "index": 2,
    "face": true,
    "faceSdfLayout": "directional-rg",
    "faceLightMap": "avatar-face-light.png",
    "faceShadow": "avatar-face-shadow.png",
    "lightMap": "avatar-lightmap.png",
    "outline": true,
    "outlineMode": "screen",
    "outlineWidth": 0.35,
    "rimIntensity": 0.3
  }]
}
```

Material entries accept `index` or an unambiguous `name`; `primitives` entries accept a mesh selector plus its `primitive` index. Version 2 face entries require separate `faceLightMap` and `faceShadow` textures and may select `mirrored-r` or `directional-rg` with `faceSdfLayout`; version 1 retains the packed `faceMap` input.

### Deriving sidecar data in game or offline

The same v2 sidecar derivation used by Celerant's Python tool lives in MCglTF as `ToonAssetGenerator`. Callers pass a loaded VRM path; the generator writes `model.vrm.toon.json` and its PNG sheets beside the model.

## Development

```bash
./gradlew build                  # api + fabric + neoforge jars, unit tests
./gradlew :common:test           # JUnit unit tests
./gradlew :fabric:runClient      # Fabric dev client
./gradlew :neoforge:runClient    # NeoForge dev client
```

## Tests

```bash
./gradlew :common:test
./gradlew :fabric:runClientGameTest
./gradlew :neoforge:runClientGameTest
./gradlew :fabric:runClientGameTest -PirisRuntime=true -PrequireIris=true -PshaderPack=/absolute/path/to/pack.zip
```

Fabric Client GameTests copy the VRM fixtures from `test_models/` and capture first-person, third-person, and entity-gaze screenshots. NeoForge uses a client render harness on `SubmitCustomGeometryEvent` with an optional smoke benchmark when `-Dmcgltf.gametest.smoke=true` is set (as in `runClientGameTest`).

VRM fixtures are intentionally excluded from Git because their licenses do not permit redistribution. Place authorized local copies at `test_models/transformed_wakgood.vrm` and `test_models/transformed_jingburger.vrm`; fixture-dependent GameTests are disabled when they are absent.

## Release

Set `version` in `gradle.properties`, commit and push the release commit, then run:

```bash
./scripts/release.sh
```

GitHub Actions builds and publishes:

- `mcgltf-api-26.2-*.jar`
- `MCglTF-Fabric-26.2-*.jar` (+ sources)
- `MCglTF-NeoForge-26.2-*.jar` (+ sources)

## Credit

- JglTF by javagl : https://github.com/javagl/JglTF
- Mikk Tangent Generator by jMonkeyEngine : https://github.com/jMonkeyEngine/jmonkeyengine

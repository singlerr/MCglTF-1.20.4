# MCglTF for Fabric 26.2

A client-side glTF/GLB/VRM model loader for Minecraft 26.2. Models are submitted through Minecraft's Blaze3D
render pipeline, so vanilla rendering, Iris 1.11.x, entity passes, and first-person hand passes share the same state
management instead of using raw OpenGL hooks.

[![](https://cf.way2muchnoise.eu/title/mcgltf.svg)](https://www.curseforge.com/minecraft/mc-mods/mcgltf) [![](https://cf.way2muchnoise.eu/versions/mcgltf.svg)](https://www.curseforge.com/minecraft/mc-mods/mcgltf) [![](https://cf.way2muchnoise.eu/mcgltf.svg)](https://www.curseforge.com/minecraft/mc-mods/mcgltf)

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.156.0+26.2
- Java 25

## Usage

Register once during client initialization. The returned handle always points at the model prepared by the latest
resource reload:

```java
GltfModelHandle model = MCglTF.getInstance().registerModel(
    Identifier.fromNamespaceAndPath("example", "models/avatar.vrm"));

if (model.isReady()) {
    model.get().submit(sceneIndex, poseStack, submitNodeCollector, packedLight, packedOverlay);
}
```

Call `model.close()` when the registration is no longer needed. `IGltfModelReceiver` remains available only for
advanced callers that need to inspect the parsed glTF before sharing a rendered model. Do not store the result of
`model.get()` across resource reloads; keep the handle and read its current value when submitting.

- https://github.com/ModularMods/MCglTF-Example

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
- [x] VRM 0.x MToon base/shade textures with a stepped normal-light ramp

MToon uses a dedicated managed Blaze3D entity pass: the VRM 0.x `VRM.materialProperties` base and shade textures are
mixed at the material's light boundary. This avoids mutating ShaderPack source or global OpenGL state. Genshin-specific
LightMap channels, face SDFs, depth rims, and inverted-hull outlines are not VRM interchange formats, so they remain
model/renderer-specific rather than being guessed from arbitrary textures. Metallic-roughness and normal maps remain
the responsibility of the active Minecraft/Iris pipeline.

## Tests

```bash
./gradlew test runClientGameTest
./gradlew runClientGameTest -PirisRuntime=true -PrequireIris=true -PshaderPack=/absolute/path/to/pack.zip
```

Client GameTests copy the VRM fixtures from `test_models/` and capture first-person, third-person, and entity-gaze
screenshots. The gaze test also verifies that rendering and then removing a VRM does not corrupt surrounding item or
world textures. The same run renders 1, 2, 4, and 8 mixed VRM instances and logs average/max submit time, frame time,
and submitted vertices. Iris tests use its OpenGL backend; Iris does not run on Minecraft 26.2's experimental Vulkan
backend.

VRM fixtures are intentionally excluded from Git because their licenses do not permit redistribution. Place authorized
local copies at `test_models/transformed_wakgood.vrm` and `test_models/transformed_jingburger.vrm`; fixture-dependent
unit tests are skipped and `runClientGameTest` is disabled when they are absent.

## Release

Set `mod_version` in `gradle.properties`, commit and push the release commit, then run:

```bash
./scripts/release.sh
```

The script creates and pushes `v<mod_version>`. GitHub Actions validates that the tag matches `mod_version`, builds
with Java 25, and creates a GitHub Release containing the remapped and sources JARs. The same release can be started
from **Actions → Release → Run workflow** by entering the exact `mod_version`; that path creates the tag in CI.

## Credit

- JglTF by javagl : https://github.com/javagl/JglTF
- Mikk Tangent Generator by jMonkeyEngine : https://github.com/jMonkeyEngine/jmonkeyengine

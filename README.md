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
- [x] VRM 0.x MToon base/shade textures with per-material shift/toony ramp and rim
- [x] Angle-limited smooth MToon normals and standard-entity inverted-hull outlines
- [x] Optional ToonShader materials with LightMap ramps, face SDF, head axes, depth rim, material outlines, matcap/specular, emission, blush, and day/night ramps

MToon uses a dedicated managed Blaze3D entity pass when ShaderPacks are off: the VRM 0.x `VRM.materialProperties` base
and shade textures are mixed at the material's `_ShadeShift`/`_ShadeToony` light boundary with a small parameter rim.
The controls travel in the generated shade texture alpha, which the managed pass restores before writing opacity.
When Iris has an active ShaderPack, ordinary MCglTF callers still use its standard entity pass and never enter the
ToonShader renderer. ToonShader is opt-in: construct `RenderedGltfModel` with a sidecar path, submit
`RenderedGltfModel.MTOON_OVERLAY_REQUEST`, capture the world projection with `ToonShader.captureProjection`, and call
`ToonShader.renderFrame()` after Iris finishes its final pass. The optional renderer shades those model primitives in
its own HDR color/depth targets, depth-tests them against the scene, then premultiplied-composites the result. It does
not transform ShaderPack GLSL or write unknown G-buffer attachments.

### Optional ToonShader sidecar

The sidecar is JSON version 2 (version 1 remains supported) and normally sits beside the model as
`model.vrm.toon.json`. PNG paths are relative to that file and must stay in the same directory tree. Invalid
material/node/primitive references, traversal, non-PNG textures, profiles over 1 MiB, or textures over 64 MiB fail
model preparation instead of guessing.

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

Material entries accept `index` or an unambiguous `name`; `primitives` entries accept a mesh selector plus its
`primitive` index. Version 2 face entries require separate `faceLightMap` and `faceShadow` textures and may select
`mirrored-r` or `directional-rg` with `faceSdfLayout`; version 1 retains the packed `faceMap` input. Texture inputs are
`baseTexture`, `shadeTexture`, `normalTexture`, `emissionTexture`, `matcapTexture`, `rimTexture`,
`outlineWidthTexture`, `lightMap`, `rampTexture`, `faceMap`, `faceLightMap`, and `faceShadow`. Controls are
`materialType`, `face`, `metallic`, `outline`, `outlineMode`, `outlineVertexAlpha`, `backUv`, `shadowOffset`,
`shadowSmoothness`, `nonMetalSpecular`, `metalSpecular`, `specularShininess`, `emissionIntensity`, `rimOffset`,
`rimThreshold`, `rimIntensity`, `rimPower`, `outlineWidth`, `outlineDistanceNear`, `outlineDistanceFar`,
`outlineScaleNear`, `outlineScaleFar`, `outlineZOffset`, `outlineLightingMix`, `faceShadowStrength`,
`faceShadowOffset`, `blushIntensity`, `shadeColor`, `emissionColor`, `rimColor`, `outlineColor`, five-entry
`outlineColors`, `blushColor`, and `[baseX, baseY, outlineX, outlineY]` `screenOffset`. Missing nonstandard inputs use
neutral textures; they are never inferred from unrelated model channels. `outlineWidth` is a percentage of screen
height in `screen` mode and centimeters in `world` mode; VRM 1.0 factors are converted to those units without adding
distance fade that the material did not request.

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

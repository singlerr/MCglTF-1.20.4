package com.modularmods.mcgltf;

import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;

import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.v2.MaterialModelV2.AlphaMode;
import net.minecraft.resources.Identifier;

final class ToonShaderMaterial {
	private final ToonShaderRenderer.Material material;

	private ToonShaderMaterial(ToonShaderRenderer.Material material) {
		this.material = material;
	}

	static ToonShaderMaterial create(ToonShaderModel model, RenderedGltfModel.TextureRegistry textures,
		RenderedGltfModel.MToonProfile mtoon, ToonShaderProfile.MaterialOverride override, Inputs inputs) {
		Identifier base = textures.resolve(inputs.baseTexture, inputs.policy);
		Identifier shade = textures.resolve(override == null ? null : override.shadeTexture(),
			textures.resolve(inputs.shadeTexture, RenderedGltfModel.AlphaPolicy.NONE));
		Identifier normal = textures.resolve(override == null ? null : override.normalTexture(),
			textures.resolve(inputs.normalTexture != null ? inputs.normalTexture
				: textures.textureAt(mtoon.normalTextureIndex()), RenderedGltfModel.AlphaPolicy.NONE,
				textures.flatNormal()));
		Identifier emission = textures.resolve(override == null ? null : override.emissionTexture(),
			textures.resolve(inputs.emissionTexture != null ? inputs.emissionTexture
				: textures.textureAt(mtoon.emissionTextureIndex()), RenderedGltfModel.AlphaPolicy.NONE, textures.white()));
		Identifier matcap = textures.resolve(override == null ? null : override.matcapTexture(),
			textures.resolve(textures.textureAt(mtoon.matcapTextureIndex()), RenderedGltfModel.AlphaPolicy.NONE,
				textures.white()));
		Identifier rim = textures.resolve(override == null ? null : override.rimTexture(),
			textures.resolve(textures.textureAt(mtoon.rimTextureIndex()), RenderedGltfModel.AlphaPolicy.NONE,
				textures.white()));
		Identifier outlineWidthTexture = textures.resolve(override == null ? null : override.outlineWidthTexture(),
			textures.resolve(textures.textureAt(mtoon.outlineWidthTextureIndex()),
				RenderedGltfModel.AlphaPolicy.NONE, textures.white()));
		Vector4f shadeColor = vector(override == null ? null : override.shadeColor(), mtoon.shadeColor());
		Vector4f emissionColor = vector(override == null ? null : override.emissionColor(), inputs.emissionColor);
		Vector4f rimColor = vector(override == null ? null : override.rimColor(), mtoon.rimColor());
		Vector4f outlineColor = vector(override == null ? null : override.outlineColor(), mtoon.outlineColor());
		Vector4f[] outlineColors = outlineColors(override, outlineColor);
		float alphaThreshold = inputs.alphaMode == AlphaMode.MASK ? inputs.alphaCutoff
			: inputs.alphaMode == AlphaMode.BLEND ? 0.001F : 0.0F;
		float emissionIntensity = value(override == null ? Float.NaN : override.emissionIntensity(),
			emissionColor.x + emissionColor.y + emissionColor.z > 0.0F ? 1.0F : 0.0F);
		boolean hasRim = rimColor.x + rimColor.y + rimColor.z > 0.0F;
		return new ToonShaderMaterial(new ToonShaderRenderer.Material(base, shade, normal, emission, matcap, rim,
			outlineWidthTexture,
			textures.resolve(override == null ? null : override.lightMap(), model.defaultLightMap(textures)),
			textures.resolve(override == null ? null : override.faceMap(), model.defaultFaceMap(textures)),
			model.ramp(textures), shadeColor, emissionColor, rimColor,
			outlineColors[0], outlineColors[1], outlineColors[2], outlineColors[3], outlineColors[4],
			vector(override == null ? null : override.blushColor(), new Vector4f(1.0F, 0.45F, 0.5F, 1.0F)),
			screenOffset(override),
			mtoon.shadeShift(), mtoon.shadingToony(),
			value(override == null ? Float.NaN : override.shadowOffset(), mtoon.shadeShift() * 0.5F),
			value(override == null ? Float.NaN : override.shadowSmoothness(),
				Math.max(0.001F, (1.0F - mtoon.shadingToony()) * 0.5F)),
			mtoon.giEqualization(), override == null ? -1.0F : override.materialType(),
			value(override == null ? Float.NaN : override.nonMetalSpecular(), 0.3F),
			value(override == null ? Float.NaN : override.metalSpecular(), 0.8F),
			value(override == null ? Float.NaN : override.specularShininess(), 10.0F), emissionIntensity,
			value(override == null ? Float.NaN : override.rimOffset(), hasRim ? 2.0F : 0.0F),
			value(override == null ? Float.NaN : override.rimThreshold(), Math.max(0.05F, 1.0F - mtoon.rimLift())),
			value(override == null ? Float.NaN : override.rimIntensity(), hasRim ? 1.0F : 0.0F),
			value(override == null ? Float.NaN : override.rimPower(), mtoon.rimFresnelPower()),
			value(override == null ? Float.NaN : override.outlineWidth(), mtoon.outlineWidth()),
			value(override == null ? Float.NaN : override.outlineDistanceNear(), 0.0F),
			value(override == null ? Float.NaN : override.outlineDistanceFar(), mtoon.outlineDistanceFar()),
			value(override == null ? Float.NaN : override.outlineScaleNear(), 1.0F),
			value(override == null ? Float.NaN : override.outlineScaleFar(),
				mtoon.outlineDistanceFade() ? 0.0F : 1.0F),
			value(override == null ? Float.NaN : override.outlineZOffset(), 0.0F),
			value(override == null ? Float.NaN : override.outlineLightingMix(), mtoon.outlineLightingMix()),
			value(override == null ? Float.NaN : override.faceShadowStrength(), 1.0F),
			value(override == null ? Float.NaN : override.faceShadowOffset(), 0.0F),
			value(override == null ? Float.NaN : override.blushIntensity(), 0.0F), alphaThreshold,
			override != null && override.face(), override != null && override.metallic(),
			mtoon.outline() || override != null && override.outline(),
			override != null ? override.outlineScreenSpace() : mtoon.outlineScreenSpace(),
			override != null && override.outlineVertexAlpha(), override != null && override.backUv(),
			inputs.doubleSided));
	}

	void queue(PoseStack.Pose pose, float[] positions, float[] normals, float[] texcoords, float[] backTexcoords,
		int[] vertexColors, int[] indices, int packedLight, ToonShaderModel.Frame frame) {
		ToonShaderRenderer.queue(pose, positions, normals, texcoords, backTexcoords, vertexColors, indices,
			packedLight, material, frame);
	}

	private static float value(float value, float fallback) {
		return Float.isNaN(value) ? fallback : value;
	}

	private static Vector4f vector(float[] value, Vector4f fallback) {
		return value == null ? new Vector4f(fallback) : new Vector4f(value[0], value[1], value[2], value[3]);
	}

	static Vector4f screenOffset(ToonShaderProfile.MaterialOverride override) {
		return vector(override == null ? null : override.screenOffset(), new Vector4f(0.0F));
	}

	private static Vector4f[] outlineColors(ToonShaderProfile.MaterialOverride override, Vector4f fallback) {
		Vector4f[] colors = {new Vector4f(fallback), new Vector4f(fallback), new Vector4f(fallback),
			new Vector4f(fallback), new Vector4f(fallback)};
		if (override != null && override.outlineColors() != null) {
			for (int i = 0; i < colors.length; i++) {
				colors[i] = vector(override.outlineColors()[i], fallback);
			}
		}
		return colors;
	}

	record Inputs(TextureModel baseTexture, TextureModel shadeTexture, TextureModel normalTexture,
		TextureModel emissionTexture, RenderedGltfModel.AlphaPolicy policy, AlphaMode alphaMode,
		float alphaCutoff, boolean doubleSided, Vector4f emissionColor) {
	}
}

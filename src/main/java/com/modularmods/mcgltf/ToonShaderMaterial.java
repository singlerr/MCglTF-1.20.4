package com.modularmods.mcgltf;

import org.joml.Vector4f;

import com.mojang.blaze3d.vertex.PoseStack;

import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.v2.MaterialModelV2.AlphaMode;
import net.minecraft.resources.Identifier;

final class ToonShaderMaterial {
	private final ToonShaderRenderer.Material material;
	private final boolean mappedNormal;

	private ToonShaderMaterial(ToonShaderRenderer.Material material, boolean mappedNormal) {
		this.material = material;
		this.mappedNormal = mappedNormal;
	}

	static ToonShaderMaterial create(ToonShaderModel model, RenderedGltfModel.TextureRegistry textures,
		RenderedGltfModel.MToonProfile mtoon, ToonShaderProfile.MaterialOverride override, Inputs inputs) {
		float normalScale = override != null && override.normalTexture() != null ? 1.0F : inputs.normalScale;
		boolean mappedNormal = normalScale != 0.0F && (inputs.normalTexture != null || mtoon.normalTextureIndex() >= 0
			|| override != null && override.normalTexture() != null);
		Identifier alpha = textures.resolve(inputs.baseTexture, inputs.policy);
		Identifier base = textures.resolve(override == null ? null : override.baseTexture(),
			alpha);
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
		Vector4f shadeColor = vector(override == null ? null : override.shadeColor(),
			override == null ? mtoon.shadeColor() : new Vector4f(1.1F));
		Vector4f emissionColor = vector(override == null ? null : override.emissionColor(), inputs.emissionColor);
		Vector4f rimColor = vector(override == null ? null : override.rimColor(), mtoon.rimColor());
		Vector4f outlineColor = vector(override == null ? null : override.outlineColor(), mtoon.outlineColor());
		Vector4f[] outlineColors = outlineColors(override, outlineColor);
		float alphaThreshold = inputs.alphaMode == AlphaMode.MASK ? inputs.alphaCutoff
			: inputs.alphaMode == AlphaMode.BLEND ? 0.001F : 0.0F;
		float emissionIntensity = value(override == null ? Float.NaN : override.emissionIntensity(),
			emissionColor.x + emissionColor.y + emissionColor.z > 0.0F ? 1.0F : 0.0F);
		boolean hasRim = rimColor.x + rimColor.y + rimColor.z > 0.0F;
		float baseColorScale = model.baseColorScale();
		Vector4f baseColor = vector(override == null ? null : override.baseColorFactor(), inputs.baseColor)
			.mul(baseColorScale, baseColorScale, baseColorScale, 1.0F);
		boolean legacyFaceInputs = override != null && override.face() && !model.officialFaceInputs();
		Identifier faceLightMap = textures.resolve(override == null ? null
			: legacyFaceInputs ? override.faceMap() : override.faceLightMap(), model.defaultFaceTexture(textures));
		Identifier faceShadow = textures.resolve(override == null ? null
			: legacyFaceInputs ? override.faceMap() : override.faceShadow(), model.defaultFaceTexture(textures));
		return new ToonShaderMaterial(new ToonShaderRenderer.Material(base, alpha, shade, normal, emission, matcap, rim,
			outlineWidthTexture,
			textures.resolve(override == null ? null : override.lightMap(), model.defaultLightMap(textures)),
			faceLightMap, faceShadow,
			textures.resolve(override == null ? null : override.rampTexture(), model.ramp(textures)),
			baseColor, shadeColor, emissionColor, rimColor,
			outlineColors[0], outlineColors[1], outlineColors[2], outlineColors[3], outlineColors[4],
			vector(override == null ? null : override.blushColor(), new Vector4f(1.0F, 0.45F, 0.5F, 1.0F)),
			screenOffset(override),
			mtoon.shadeShift(), mtoon.shadingToony(),
			value(override == null ? Float.NaN : override.shadowOffset(),
				override == null ? mtoon.shadeShift() * 0.5F : 0.1F),
			value(override == null ? Float.NaN : override.shadowSmoothness(),
				override == null ? Math.max(0.001F, (1.0F - mtoon.shadingToony()) * 0.5F) : 0.4F),
			mtoon.giEqualization(), override == null ? -1.0F : override.materialType(),
			value(override == null ? Float.NaN : override.nonMetalSpecular(), override == null ? 0.3F : 0.0F),
			value(override == null ? Float.NaN : override.metalSpecular(), override == null ? 0.8F : 0.0F),
			value(override == null ? Float.NaN : override.specularShininess(), override == null ? 10.0F : 5.0F),
			emissionIntensity,
			value(override == null ? Float.NaN : override.rimOffset(),
				override == null ? (hasRim ? 2.0F : 0.0F) : 5.0F),
			value(override == null ? Float.NaN : override.rimThreshold(),
				override == null ? Math.max(0.05F, 1.0F - mtoon.rimLift()) : 0.5F),
			value(override == null ? Float.NaN : override.rimIntensity(),
				override == null && hasRim ? 1.0F : 0.0F),
			value(override == null ? Float.NaN : override.rimPower(), override == null ? mtoon.rimFresnelPower() : 5.0F),
			value(override == null ? Float.NaN : override.outlineWidth(), override == null ? mtoon.outlineWidth() : 0.0F),
			value(override == null ? Float.NaN : override.outlineDistanceNear(), 0.0F),
			value(override == null ? Float.NaN : override.outlineDistanceFar(),
				override == null ? mtoon.outlineDistanceFar() : 1.0F),
			value(override == null ? Float.NaN : override.outlineScaleNear(), override == null ? 1.0F : 0.0F),
			value(override == null ? Float.NaN : override.outlineScaleFar(),
				override == null ? (mtoon.outlineDistanceFade() ? 0.0F : 1.0F) : 1.0F),
			value(override == null ? Float.NaN : override.outlineZOffset(), 0.0F),
			value(override == null ? Float.NaN : override.outlineLightingMix(),
				override == null ? mtoon.outlineLightingMix() : 0.0F),
			value(override == null ? Float.NaN : override.faceShadowStrength(), 1.0F),
			value(override == null ? Float.NaN : override.faceShadowOffset(), 0.0F),
			value(override == null ? Float.NaN : override.blushIntensity(), 0.0F), alphaThreshold, normalScale,
			override != null && override.face(), override != null && override.metallic(),
			mtoon.outline() || override != null && override.outline(),
			override != null ? override.outlineScreenSpace() : mtoon.outlineScreenSpace(),
			override != null && override.outlineVertexAlpha(), override != null && override.backUv(),
			override != null && override.directionalFaceSdf(), legacyFaceInputs,
			inputs.alphaMode != AlphaMode.OPAQUE,
			inputs.alphaMode == AlphaMode.BLEND, inputs.doubleSided, override != null), mappedNormal);
	}

	boolean requiresMappedTangents() {
		return mappedNormal;
	}

	void queue(PoseStack.Pose pose, float[] positions, float[] normals, float[] tangents, float[] smoothNormals,
		float[] texcoords, float[] backTexcoords, int[] vertexColors, int[] indices, int packedLight,
		ToonShaderModel.Frame frame) {
		ToonShaderRenderer.queue(pose, positions, normals, tangents, smoothNormals, texcoords, backTexcoords,
			vertexColors, indices, packedLight, material, frame);
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
		float alphaCutoff, boolean doubleSided, Vector4f baseColor, Vector4f emissionColor, float normalScale) {
	}
}

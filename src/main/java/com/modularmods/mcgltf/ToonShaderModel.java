package com.modularmods.mcgltf;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;

final class ToonShaderModel {
	private final ToonShaderProfile profile;
	private final Map<MaterialModel, ToonShaderProfile.MaterialOverride> materials = new IdentityHashMap<>();
	private final Map<MeshPrimitiveModel, ToonShaderProfile.MaterialOverride> primitives = new IdentityHashMap<>();
	private Identifier ramp;
	private Identifier defaultLightMap;
	private Identifier defaultFaceTexture;

	private ToonShaderModel(GltfModel model, Path profilePath) {
		profile = ToonShaderProfile.load(model, profilePath);
		profile.materials.forEach((index, material) -> materials.put(model.getMaterialModels().get(index), material));
		primitives.putAll(profile.primitives);
	}

	static ToonShaderModel load(GltfModel model, Path profilePath) {
		return new ToonShaderModel(model, profilePath);
	}

	boolean applies(MeshPrimitiveModel primitive, MaterialModel material, RenderedGltfModel.MToonProfile mtoon) {
		return mtoon.enabled() || primitives.containsKey(primitive) || materials.containsKey(material);
	}

	ToonShaderMaterial material(MeshPrimitiveModel primitive, MaterialModel material,
		RenderedGltfModel.TextureRegistry textures,
		RenderedGltfModel.MToonProfile mtoon, ToonShaderMaterial.Inputs inputs) {
		ToonShaderProfile.MaterialOverride override = primitives.getOrDefault(primitive, materials.get(material));
		return applies(primitive, material, mtoon)
			? ToonShaderMaterial.create(this, textures, mtoon, override, inputs) : null;
	}

	Frame frame(PoseStack poseStack, RenderedGltfModel.FrameSnapshots snapshots) {
		Matrix4f transform = new Matrix4f(poseStack.last().pose());
		if (profile.head != null) {
			transform.mul(snapshots.nodeTransform(profile.head));
		}
		Vector3f forward = transform.transformDirection(new Vector3f(profile.headForward)).normalize();
		Vector3f right = transform.transformDirection(new Vector3f(profile.headRight)).normalize();
		Minecraft minecraft = Minecraft.getInstance();
		float night = minecraft.level == null ? 0.0F
			: Math.max(0.0F, Math.min(1.0F, minecraft.level.getSkyDarken() / 15.0F));
		Vector3f mainLight = new Vector3f(0.0F, 1.0F, 0.0F);
		if (minecraft.level != null && minecraft.gameRenderer.mainCamera().isInitialized()) {
			float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
			Vector3f sun = celestialDirection(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick));
			Vector3f moon = celestialDirection(probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick));
			mainLight.set(sun.y >= moon.y ? sun : moon);
		}
		mainLight.mul(profile.lightDirectionMultiplier).normalize();
		return new Frame(forward, right, mainLight, night);
	}

	static Vector3f celestialDirection(float degrees) {
		float radians = degrees * Mth.DEG_TO_RAD;
		return new Vector3f(-Mth.sin(radians), Mth.cos(radians), 0.0F);
	}

	Identifier ramp(RenderedGltfModel.TextureRegistry textures) {
		if (ramp == null) {
			ramp = profile.rampTexture == null
				? textures.register(defaultRamp(), null, true) : textures.resolve(profile.rampTexture, textures.white());
		}
		return ramp;
	}

	Identifier defaultLightMap(RenderedGltfModel.TextureRegistry textures) {
		if (defaultLightMap == null) {
			defaultLightMap = textures.solid(0xFF00FF00);
		}
		return defaultLightMap;
	}

	Identifier defaultFaceTexture(RenderedGltfModel.TextureRegistry textures) {
		if (defaultFaceTexture == null) {
			defaultFaceTexture = textures.white();
		}
		return defaultFaceTexture;
	}

	boolean officialFaceInputs() {
		return profile.version >= 2;
	}

	boolean allowGeneratedSmoothNormals() {
		return profile.generateSmoothNormals;
	}

	float smoothNormalCosine() {
		return profile.smoothNormalCosine;
	}

	float baseColorScale() {
		return profile.baseColorScale;
	}

	private static NativeImage defaultRamp() {
		int[][] night = {{72, 56, 78}, {52, 64, 88}, {78, 54, 48}, {54, 72, 66}, {62, 58, 74}};
		int[][] day = {{126, 82, 92}, {78, 104, 142}, {140, 82, 64}, {76, 118, 92}, {100, 86, 118}};
		NativeImage image = new NativeImage(256, 10, false);
		for (int y = 0; y < 10; y++) {
			int[] shadow = (y < 5 ? night : day)[y % 5];
			for (int x = 0; x < 256; x++) {
				float t = x / 255.0F;
				int red = Math.round(shadow[0] + (255 - shadow[0]) * t);
				int green = Math.round(shadow[1] + (255 - shadow[1]) * t);
				int blue = Math.round(shadow[2] + (255 - shadow[2]) * t);
				image.setPixel(x, y, 0xFF000000 | blue << 16 | green << 8 | red);
			}
		}
		return image;
	}

	record Frame(Vector3f headForward, Vector3f headRight, Vector3f mainLightDirection, float night) {
	}
}

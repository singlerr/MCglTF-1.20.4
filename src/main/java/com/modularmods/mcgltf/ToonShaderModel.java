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

	/**
	 * Neutral light map for models that ship no authored one. Following the Genshin
	 * channel convention this is no specular, a green shadow threshold of one half so
	 * the Lambert term alone decides where the terminator falls, no highlight, and an
	 * alpha that selects the shader's fallback ramp band. A fully green pixel would
	 * read as "always lit" and leave every unprofiled model unshaded.
	 */
	Identifier defaultLightMap(RenderedGltfModel.TextureRegistry textures) {
		if (defaultLightMap == null) {
			defaultLightMap = textures.solid(0x00008000);
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

	/**
	 * Fallback shadow ramp laid out like the official Genshin ramp sheets: five
	 * material bands per daylight state, ordered for Unity's bottom-up V axis so the
	 * daylight bands sit in the top half. Band order follows the material index the
	 * shader derives from the light map alpha, which is soft cloth, skin, hair, metal
	 * and then everything else.
	 */
	private static NativeImage defaultRamp() {
		int[][] day = {{132, 116, 126}, {160, 112, 112}, {120, 116, 148}, {100, 106, 134},
			{130, 126, 138}};
		int[][] night = {{86, 82, 116}, {102, 78, 100}, {76, 80, 130}, {64, 74, 116}, {84, 88, 122}};
		NativeImage image = new NativeImage(256, 10, false);
		for (int band = 0; band < 5; band++) {
			writeRampRow(image, 4 - band, day[band]);
			writeRampRow(image, 9 - band, night[band]);
		}
		return image;
	}

	private static void writeRampRow(NativeImage image, int y, int[] shadow) {
		for (int x = 0; x < 256; x++) {
			// Official sheets hold the shadow colour flat and only lift to white near
			// the right edge, which keeps the terminator readable at any smoothness.
			float t = x / 255.0F;
			float lift = Mth.clamp((t - 0.72F) / 0.28F, 0.0F, 1.0F);
			lift = lift * lift * (3.0F - 2.0F * lift);
			int red = Math.round(shadow[0] + (255 - shadow[0]) * lift);
			int green = Math.round(shadow[1] + (255 - shadow[1]) * lift);
			int blue = Math.round(shadow[2] + (255 - shadow[2]) * lift);
			image.setPixel(x, y, 0xFF000000 | blue << 16 | green << 8 | red);
		}
	}

	record Frame(Vector3f headForward, Vector3f headRight, Vector3f mainLightDirection, float night) {
	}
}

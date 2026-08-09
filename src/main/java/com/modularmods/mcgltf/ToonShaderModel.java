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
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class ToonShaderModel {
	private final ToonShaderProfile profile;
	private final Map<MaterialModel, ToonShaderProfile.MaterialOverride> materials = new IdentityHashMap<>();
	private Identifier ramp;
	private Identifier defaultLightMap;
	private Identifier defaultFaceMap;

	private ToonShaderModel(GltfModel model, Path profilePath) {
		profile = ToonShaderProfile.load(model, profilePath);
		profile.materials.forEach((index, material) -> materials.put(model.getMaterialModels().get(index), material));
	}

	static ToonShaderModel load(GltfModel model, Path profilePath) {
		return new ToonShaderModel(model, profilePath);
	}

	boolean applies(MaterialModel material, RenderedGltfModel.MToonProfile mtoon) {
		return mtoon.enabled() || materials.containsKey(material);
	}

	ToonShaderMaterial material(MaterialModel material, RenderedGltfModel.TextureRegistry textures,
		RenderedGltfModel.MToonProfile mtoon, ToonShaderMaterial.Inputs inputs) {
		return applies(material, mtoon)
			? ToonShaderMaterial.create(this, textures, mtoon, materials.get(material), inputs) : null;
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
		return new Frame(forward, right, new Vector3f(profile.lightDirectionMultiplier), night);
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

	Identifier defaultFaceMap(RenderedGltfModel.TextureRegistry textures) {
		if (defaultFaceMap == null) {
			defaultFaceMap = textures.solid(0x0000FFFF);
		}
		return defaultFaceMap;
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

	record Frame(Vector3f headForward, Vector3f headRight, Vector3f lightDirectionMultiplier, float night) {
	}
}

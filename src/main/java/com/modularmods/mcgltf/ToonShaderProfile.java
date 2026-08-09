package com.modularmods.mcgltf;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.NodeModel;

final class ToonShaderProfile {
	private static final long MAX_PROFILE_BYTES = 1024L * 1024L;
	private static final long MAX_TEXTURE_BYTES = 64L * 1024L * 1024L;
	private static final Vector3f DEFAULT_FORWARD = new Vector3f(0.0F, 0.0F, -1.0F);
	private static final Vector3f DEFAULT_RIGHT = new Vector3f(1.0F, 0.0F, 0.0F);
	private static final Vector3f DEFAULT_LIGHT_MULTIPLIER = new Vector3f(1.0F, 0.5F, 1.0F);

	final NodeModel head;
	final Vector3f headForward;
	final Vector3f headRight;
	final Vector3f lightDirectionMultiplier;
	final Path rampTexture;
	final Map<Integer, MaterialOverride> materials;

	private ToonShaderProfile(NodeModel head, Vector3f headForward, Vector3f headRight,
		Vector3f lightDirectionMultiplier, Path rampTexture, Map<Integer, MaterialOverride> materials) {
		this.head = head;
		this.headForward = headForward;
		this.headRight = headRight;
		this.lightDirectionMultiplier = lightDirectionMultiplier;
		this.rampTexture = rampTexture;
		this.materials = Map.copyOf(materials);
	}

	static ToonShaderProfile load(GltfModel model, Path profilePath) {
		NodeModel head = humanoidHead(model);
		if (profilePath == null || !Files.isRegularFile(profilePath)) {
			return new ToonShaderProfile(head, new Vector3f(DEFAULT_FORWARD), new Vector3f(DEFAULT_RIGHT),
				new Vector3f(DEFAULT_LIGHT_MULTIPLIER), null, Map.of());
		}

		try {
			if (Files.size(profilePath) > MAX_PROFILE_BYTES) {
				throw new IOException("ToonShader profile is larger than 1 MiB");
			}
			JsonObject json;
			try (Reader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (!parsed.isJsonObject()) {
					throw new IOException("ToonShader profile root must be an object");
				}
				json = parsed.getAsJsonObject();
			}
			if (integer(json, "version", -1) != 1) {
				throw new IOException("ToonShader profile version must be 1");
			}

			Path root = profilePath.toAbsolutePath().normalize().getParent();
			JsonObject headJson = object(json, "head");
			if (headJson != null) {
				head = node(model, headJson, head);
			}
			Vector3f forward = vector(headJson, "forward", DEFAULT_FORWARD);
			Vector3f right = vector(headJson, "right", DEFAULT_RIGHT);
			orthonormalize(forward, right);
			Vector3f lightMultiplier = vector(json, "lightDirectionMultiplier", DEFAULT_LIGHT_MULTIPLIER);
			if (lightMultiplier.x < 0.0F || lightMultiplier.y < 0.0F || lightMultiplier.z < 0.0F
				|| lightMultiplier.lengthSquared() < 1.0E-8F) {
				throw new IOException("lightDirectionMultiplier must contain non-negative, non-zero values");
			}
			Path ramp = texture(root, string(json, "rampTexture"));
			Map<Integer, MaterialOverride> overrides = materialOverrides(model, root, array(json, "materials"));
			return new ToonShaderProfile(head, forward, right, lightMultiplier, ramp, overrides);
		} catch (IOException | RuntimeException exception) {
			throw new IllegalArgumentException("Could not read " + profilePath.getFileName() + ": " + exception.getMessage(), exception);
		}
	}

	private static Map<Integer, MaterialOverride> materialOverrides(GltfModel model, Path root, JsonArray values)
		throws IOException {
		if (values == null) {
			return Map.of();
		}
		Map<Integer, MaterialOverride> result = new HashMap<>();
		for (JsonElement value : values) {
			if (!value.isJsonObject()) {
				throw new IOException("materials entries must be objects");
			}
			JsonObject json = value.getAsJsonObject();
			int material = material(model, json);
			if (result.put(material, MaterialOverride.read(root, json)) != null) {
				throw new IOException("material " + material + " is configured more than once");
			}
		}
		return result;
	}

	private static int material(GltfModel model, JsonObject json) throws IOException {
		int index = integer(json, "index", -1);
		String name = string(json, "name");
		List<MaterialModel> materials = model.getMaterialModels();
		if (index >= 0) {
			if (index >= materials.size()) {
				throw new IOException("material index " + index + " is out of range");
			}
			if (name != null && !name.equals(materials.get(index).getName())) {
				throw new IOException("material index/name do not identify the same material");
			}
			return index;
		}
		if (name == null || name.isBlank()) {
			throw new IOException("material entry needs index or name");
		}
		int found = -1;
		for (int i = 0; i < materials.size(); i++) {
			if (name.equals(materials.get(i).getName())) {
				if (found >= 0) {
					throw new IOException("material name " + name + " is ambiguous; use index");
				}
				found = i;
			}
		}
		if (found < 0) {
			throw new IOException("material " + name + " was not found");
		}
		return found;
	}

	private static NodeModel node(GltfModel model, JsonObject json, NodeModel fallback) throws IOException {
		int index = integer(json, "index", -1);
		String name = string(json, "name");
		List<NodeModel> nodes = model.getNodeModels();
		if (index >= 0) {
			if (index >= nodes.size()) {
				throw new IOException("head node index is out of range");
			}
			return nodes.get(index);
		}
		if (name == null) {
			return fallback;
		}
		NodeModel found = null;
		for (NodeModel node : nodes) {
			if (name.equals(node.getName())) {
				if (found != null) {
					throw new IOException("head node name is ambiguous; use index");
				}
				found = node;
			}
		}
		if (found == null) {
			throw new IOException("head node " + name + " was not found");
		}
		return found;
	}

	private static NodeModel humanoidHead(GltfModel model) {
		Map<String, Object> extensions = model.getExtensions();
		if (extensions == null) {
			return null;
		}
		Object vrm0 = extensions.get("VRM");
		if (vrm0 instanceof Map<?, ?> vrm && vrm.get("humanoid") instanceof Map<?, ?> humanoid
			&& humanoid.get("humanBones") instanceof List<?> bones) {
			for (Object value : bones) {
				if (value instanceof Map<?, ?> bone && "head".equals(bone.get("bone")) && bone.get("node") instanceof Number node) {
					int index = node.intValue();
					return index >= 0 && index < model.getNodeModels().size() ? model.getNodeModels().get(index) : null;
				}
			}
		}
		Object vrm1 = extensions.get("VRMC_vrm");
		if (vrm1 instanceof Map<?, ?> vrm && vrm.get("humanoid") instanceof Map<?, ?> humanoid
			&& humanoid.get("humanBones") instanceof Map<?, ?> bones && bones.get("head") instanceof Map<?, ?> head
			&& head.get("node") instanceof Number node) {
			int index = node.intValue();
			return index >= 0 && index < model.getNodeModels().size() ? model.getNodeModels().get(index) : null;
		}
		return null;
	}

	private static void orthonormalize(Vector3f forward, Vector3f right) throws IOException {
		if (forward.lengthSquared() < 1.0E-8F || right.lengthSquared() < 1.0E-8F) {
			throw new IOException("head forward/right axes must be non-zero");
		}
		forward.normalize();
		right.fma(-right.dot(forward), forward);
		if (right.lengthSquared() < 1.0E-8F) {
			throw new IOException("head forward/right axes must not be parallel");
		}
		right.normalize();
	}

	private static Path texture(Path root, String value) throws IOException {
		if (value == null) {
			return null;
		}
		Path path = root.resolve(value).normalize();
		if (!path.startsWith(root) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")
			|| !Files.isRegularFile(path) || Files.size(path) > MAX_TEXTURE_BYTES) {
			throw new IOException("texture must be a PNG file inside the profile directory and no larger than 64 MiB: " + value);
		}
		return path;
	}

	private static JsonObject object(JsonObject parent, String name) {
		if (parent == null) {
			return null;
		}
		JsonElement value = parent.get(name);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	private static String string(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? value.getAsString() : null;
	}

	private static int integer(JsonObject parent, String name, int fallback) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
			? value.getAsInt() : fallback;
	}

	private static float number(JsonObject parent, String name, float fallback) throws IOException {
		JsonElement value = parent == null ? null : parent.get(name);
		if (value == null) {
			return fallback;
		}
		float result = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
			? value.getAsFloat() : Float.NaN;
		if (!Float.isFinite(result)) {
			throw new IOException(name + " must be finite");
		}
		return result;
	}

	private static boolean bool(JsonObject parent, String name, boolean fallback) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			? value.getAsBoolean() : fallback;
	}

	private static Vector3f vector(JsonObject parent, String name, Vector3f fallback) throws IOException {
		JsonElement value = parent == null ? null : parent.get(name);
		if (value == null) {
			return new Vector3f(fallback);
		}
		if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
			throw new IOException(name + " must contain three numbers");
		}
		JsonArray array = value.getAsJsonArray();
		Vector3f result = new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
		if (!Float.isFinite(result.x) || !Float.isFinite(result.y) || !Float.isFinite(result.z)) {
			throw new IOException(name + " must contain finite numbers");
		}
		return result;
	}

	private static float[] color(JsonObject parent, String name) throws IOException {
		JsonElement value = parent == null ? null : parent.get(name);
		if (value == null) {
			return null;
		}
		if (!value.isJsonArray() || value.getAsJsonArray().size() < 3 || value.getAsJsonArray().size() > 4) {
			throw new IOException(name + " must contain three or four numbers");
		}
		JsonArray array = value.getAsJsonArray();
		float[] result = {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(),
			array.size() == 4 ? array.get(3).getAsFloat() : 1.0F};
		for (float channel : result) {
			if (!Float.isFinite(channel) || channel < 0.0F || channel > 1.0F) {
				throw new IOException(name + " channels must be between 0 and 1");
			}
		}
		return result;
	}

	private static float[] vector4(JsonObject parent, String name) throws IOException {
		JsonElement value = parent == null ? null : parent.get(name);
		if (value == null) {
			return null;
		}
		if (!value.isJsonArray() || value.getAsJsonArray().size() != 4) {
			throw new IOException(name + " must contain four numbers");
		}
		float[] result = new float[4];
		for (int i = 0; i < result.length; i++) {
			result[i] = value.getAsJsonArray().get(i).getAsFloat();
			if (!Float.isFinite(result[i])) {
				throw new IOException(name + " must contain finite numbers");
			}
		}
		return result;
	}

	private static float[][] colors(JsonObject parent, String name) throws IOException {
		JsonElement value = parent == null ? null : parent.get(name);
		if (value == null) {
			return null;
		}
		if (!value.isJsonArray() || value.getAsJsonArray().size() != 5) {
			throw new IOException(name + " must contain five colors");
		}
		float[][] result = new float[5][];
		for (int i = 0; i < result.length; i++) {
			JsonObject wrapper = new JsonObject();
			wrapper.add("color", value.getAsJsonArray().get(i));
			result[i] = color(wrapper, "color");
		}
		return result;
	}

	record MaterialOverride(
		Path shadeTexture, Path normalTexture, Path emissionTexture, Path matcapTexture, Path rimTexture,
		Path outlineWidthTexture, Path lightMap, Path faceMap, int materialType, boolean face,
		boolean metallic, boolean outline, boolean outlineScreenSpace, boolean outlineVertexAlpha,
		boolean backUv, float shadowOffset, float shadowSmoothness, float nonMetalSpecular,
		float metalSpecular, float specularShininess, float emissionIntensity, float rimOffset,
		float rimThreshold, float rimIntensity, float rimPower, float outlineWidth, float outlineDistanceNear,
		float outlineDistanceFar, float outlineScaleNear, float outlineScaleFar, float outlineZOffset,
		float outlineLightingMix, float faceShadowStrength,
		float faceShadowOffset, float blushIntensity, float[] shadeColor, float[] emissionColor, float[] rimColor,
		float[] outlineColor, float[][] outlineColors, float[] blushColor, float[] screenOffset) {

		static MaterialOverride read(Path root, JsonObject json) throws IOException {
			int materialType = integer(json, "materialType", -1);
			if (materialType < -1 || materialType > 4) {
				throw new IOException("materialType must be between 0 and 4");
			}
			String outlineMode = string(json, "outlineMode");
			if (outlineMode != null && !outlineMode.equals("world") && !outlineMode.equals("screen")) {
				throw new IOException("outlineMode must be world or screen");
			}
			MaterialOverride result = new MaterialOverride(
				texture(root, string(json, "shadeTexture")), texture(root, string(json, "normalTexture")),
				texture(root, string(json, "emissionTexture")), texture(root, string(json, "matcapTexture")),
				texture(root, string(json, "rimTexture")), texture(root, string(json, "outlineWidthTexture")),
				texture(root, string(json, "lightMap")), texture(root, string(json, "faceMap")), materialType,
				bool(json, "face", false), bool(json, "metallic", false), bool(json, "outline", false),
				"screen".equals(outlineMode), bool(json, "outlineVertexAlpha", false), bool(json, "backUv", false),
				number(json, "shadowOffset", Float.NaN), number(json, "shadowSmoothness", Float.NaN),
				number(json, "nonMetalSpecular", Float.NaN), number(json, "metalSpecular", Float.NaN),
				number(json, "specularShininess", Float.NaN), number(json, "emissionIntensity", Float.NaN),
				number(json, "rimOffset", Float.NaN), number(json, "rimThreshold", Float.NaN),
				number(json, "rimIntensity", Float.NaN), number(json, "rimPower", Float.NaN),
				number(json, "outlineWidth", Float.NaN), number(json, "outlineDistanceNear", Float.NaN),
				number(json, "outlineDistanceFar", Float.NaN), number(json, "outlineScaleNear", Float.NaN),
				number(json, "outlineScaleFar", Float.NaN), number(json, "outlineZOffset", Float.NaN),
				number(json, "outlineLightingMix", Float.NaN), number(json, "faceShadowStrength", Float.NaN),
				number(json, "faceShadowOffset", Float.NaN), number(json, "blushIntensity", Float.NaN),
				color(json, "shadeColor"), color(json, "emissionColor"),
				color(json, "rimColor"), color(json, "outlineColor"), colors(json, "outlineColors"),
				color(json, "blushColor"), vector4(json, "screenOffset"));
			if (result.face && result.faceMap == null) {
				throw new IOException("face materials require faceMap");
			}
			nonNegative("shadowSmoothness", result.shadowSmoothness);
			nonNegative("nonMetalSpecular", result.nonMetalSpecular);
			nonNegative("metalSpecular", result.metalSpecular);
			nonNegative("specularShininess", result.specularShininess);
			nonNegative("emissionIntensity", result.emissionIntensity);
			nonNegative("rimThreshold", result.rimThreshold);
			nonNegative("rimIntensity", result.rimIntensity);
			nonNegative("rimPower", result.rimPower);
			nonNegative("outlineWidth", result.outlineWidth);
			nonNegative("faceShadowStrength", result.faceShadowStrength);
			nonNegative("blushIntensity", result.blushIntensity);
			return result;
		}

		private static void nonNegative(String name, float value) throws IOException {
			if (!Float.isNaN(value) && value < 0.0F) {
				throw new IOException(name + " must be non-negative");
			}
		}
	}
}

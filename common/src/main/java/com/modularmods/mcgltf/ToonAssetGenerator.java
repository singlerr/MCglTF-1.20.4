package com.modularmods.mcgltf;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.io.GltfModelReader;

import com.modularmods.mcgltf.ToonMaterialClassifier.Category;

/**
 * Derives a complete Genshin style toon profile for a VRM: the tone sheets, the
 * per-material LightMaps, the facial SDF pair, and the profile that binds them.
 *
 * <p>This is the same derivation the offline tool in the Celerant repository performs,
 * so that a model can be profiled without leaving the game and get the same result.
 * Where the model genuinely lacks the data a feature needs, that feature is left
 * unconfigured and reported rather than approximated with something generic.
 */
public final class ToonAssetGenerator {
	/**
	 * How tightly each material concentrates its highlight. The reference gates a
	 * non-metal highlight behind {@code lightMap.b + blinnPhong >= 1.1}, so the
	 * shininess and the LightMap's blue threshold together decide the highlight's size.
	 */
	private record Specular(String key, double intensity, double shininess) {
	}

	private static final Map<Category, Specular> SPECULAR = new EnumMap<>(Map.of(
		Category.SKIN, new Specular("nonMetalSpecular", 0.15, 15.0),
		Category.CLOTH, new Specular("nonMetalSpecular", 0.40, 20.0),
		Category.HAIR, new Specular("nonMetalSpecular", 0.80, 30.0),
		Category.METAL, new Specular("metalSpecular", 0.90, 50.0),
		Category.EYE, new Specular("nonMetalSpecular", 0.60, 40.0),
		Category.FACE, new Specular("nonMetalSpecular", 0.15, 15.0)));

	/** What the derivation produced, so the caller can say what still needs authoring. */
	public record Result(Path profile, int materials, boolean faceProfiled) {
	}

	private ToonAssetGenerator() {
	}

	public static Result generate(Path model, Path directory, String prefix, Path profile)
		throws IOException {
		return generate(new GltfModelReader().read(model), directory, prefix, profile);
	}

	static Result generate(GltfModel model, Path directory, String prefix, Path profile)
		throws IOException {
		ToonVrmData vrm = ToonVrmData.of(model);
		String bodyRamp = prefix + "-ramp.png";
		String hairRamp = prefix + "-ramp-hair.png";
		String matcap = prefix + "-matcap-metal.png";
		ToonToneSheets.bodyRamp().write(directory.resolve(bodyRamp));
		ToonToneSheets.hairRamp().write(directory.resolve(hairRamp));
		ToonToneSheets.matcap().write(directory.resolve(matcap));

		ToonMaterialClassifier.Result classified = new ToonMaterialClassifier(model, vrm).classify();
		ToonMaterialSheets sheets = new ToonMaterialSheets(model, vrm);
		Integer faceIndex = classified.faceMaterial();
		String faceLightMap = null;
		String faceShadow = null;
		if (faceIndex != null) {
			ToonMaterialSheets.FaceSheets face = sheets.faceSheets(faceIndex);
			faceLightMap = prefix + "-face.png";
			faceShadow = prefix + "-face-shadow.png";
			face.light().write(directory.resolve(faceLightMap));
			face.shadow().write(directory.resolve(faceShadow));
		}

		List<Object> materials = new ArrayList<>();
		for (int index = 0; index < model.getMaterialModels().size(); index++) {
			Category category = classified.categories().get(index);
			String lightMap = prefix + "-light-" + index + ".png";
			sheets.lightMap(index, category).write(directory.resolve(lightMap));
			boolean isFace = faceIndex != null && faceIndex == index;
			boolean isHair = category == Category.HAIR;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("index", index);
			entry.put("lightMap", lightMap);
			entry.put("rampTexture", isHair ? hairRamp : bodyRamp);
			entry.put("outline", true);
			entry.put("outlineMode", "screen");
			// Screen-space outlines resolve to roughly twice the width in pixels, so
			// anything below about 0.5 breaks up into a dotted line.
			entry.put("outlineWidth",
				category == Category.HAIR || category == Category.CLOTH ? 1.10 : 0.90);
			entry.put("outlineZOffset", 1.0);
			entry.put("outlineColor", box(sheets.outlineTint(index)));
			// The reference leaves the terminator at half Lambert and widens it with
			// smoothness alone; the ramp gradient is only visible across this band.
			entry.put("shadowOffset", 0.0);
			entry.put("shadowSmoothness", 0.30);
			entry.put("shadeColor", List.of(1.0, 1.0, 1.0, 1.0));
			// A screen-space pixel offset, so it has to be sized in pixels for the
			// depth difference to register at all.
			entry.put("rimOffset", isHair && !isFace ? 5.0 : 4.0);
			entry.put("rimThreshold", 0.15);
			entry.put("rimIntensity", isHair && !isFace ? 0.6 : 0.45);
			entry.put("rimPower", 5.0);
			Specular specular = SPECULAR.get(category);
			entry.put(specular.key(), specular.intensity());
			entry.put("specularShininess", specular.shininess());
			if (isFace) {
				entry.put("face", true);
				entry.put("faceSdfLayout", "directional-rg");
				entry.put("faceLightMap", faceLightMap);
				entry.put("faceShadow", faceShadow);
				entry.put("blushIntensity", 0.45);
				entry.put("faceShadowStrength", 1.0);
			} else if (category == Category.METAL) {
				entry.put("matcapTexture", matcap);
				entry.put("metallic", true);
			}
			materials.add(entry);
		}

		Map<String, Object> head = new LinkedHashMap<>();
		head.put("forward", axis(vrm.headForward().z, 2));
		head.put("right", axis(vrm.headRight().x, 0));
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("version", 2);
		// The head node itself is left out so the loader resolves it from the VRM
		// humanoid extension, which works for both VRM 0.x and VRM 1.0.
		root.put("head", head);
		root.put("lightDirectionMultiplier", List.of(1.0, 0.55, 1.0));
		root.put("smoothNormals", "generate");
		root.put("smoothNormalAngle", 180);
		root.put("baseColorScale", 1.0);
		root.put("rampTexture", bodyRamp);
		root.put("materials", materials);

		StringBuilder json = new StringBuilder();
		append(json, root, 0);
		json.append('\n');
		Files.writeString(profile, json.toString(), StandardCharsets.UTF_8);
		return new Result(profile, materials.size(), faceIndex != null);
	}

	private static List<Object> axis(float value, int component) {
		List<Object> axis = new ArrayList<>(List.of(0.0, 0.0, 0.0));
		axis.set(component, (double)value);
		return axis;
	}

	private static List<Object> box(double[] values) {
		List<Object> boxed = new ArrayList<>(values.length);
		for (double value : values) {
			boxed.add(value);
		}
		return boxed;
	}

	private static void append(StringBuilder out, Object value, int depth) {
		if (value instanceof Map<?, ?> map) {
			appendBlock(out, map.entrySet(), depth, '{', '}', (entry, indent) -> {
				out.append(quote(String.valueOf(entry.getKey()))).append(": ");
				append(out, entry.getValue(), indent);
			});
			return;
		}
		if (value instanceof List<?> list) {
			appendBlock(out, list, depth, '[', ']', (element, indent) -> append(out, element, indent));
			return;
		}
		if (value instanceof String text) {
			out.append(quote(text));
			return;
		}
		if (value instanceof Double number) {
			out.append(number(number));
			return;
		}
		out.append(value);
	}

	private interface Element<T> {
		void append(T value, int depth);
	}

	private static <T> void appendBlock(StringBuilder out, Iterable<T> values, int depth, char open,
		char close, Element<T> element) {
		out.append(open);
		boolean first = true;
		for (T value : values) {
			out.append(first ? "\n" : ",\n").append("  ".repeat(depth + 1));
			element.append(value, depth + 1);
			first = false;
		}
		if (!first) {
			out.append('\n').append("  ".repeat(depth));
		}
		out.append(close);
	}

	/**
	 * The shortest decimal that reads back as the same value, with whole numbers keeping
	 * a trailing zero. This is the form the offline tool writes, which keeps the two
	 * profiles comparable as text rather than only as parsed numbers.
	 */
	private static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1.0E16) {
			return (long)value + ".0";
		}
		return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
	}

	private static String quote(String text) {
		StringBuilder out = new StringBuilder(text.length() + 2).append('"');
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			switch (character) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (character < 0x20 || character > 0x7E) {
						out.append(String.format("\\u%04x", (int)character));
					} else {
						out.append(character);
					}
				}
			}
		}
		return out.append('"').toString();
	}
}

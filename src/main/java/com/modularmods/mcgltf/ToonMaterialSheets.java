package com.modularmods.mcgltf;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.v2.MaterialModelV2;

import com.modularmods.mcgltf.ToonMaterialClassifier.Category;

/**
 * Derives the per-material sheets a Genshin style material reads: the LightMap that
 * says how each texel responds to light, the pair of face sheets that sweep facial
 * shadow with the light direction, and the outline colour.
 *
 * <p>Everything here is measured from the model itself, so a character the code has
 * never seen gets its own data rather than a generic approximation. The offline tool in
 * the Celerant repository derives the same sheets and the two must agree.
 */
final class ToonMaterialSheets {
	/**
	 * The Genshin ILM convention: red is specular intensity and doubles as the metal
	 * selector above 0.9, green is the shadow threshold where 0.5 means "let Lambert
	 * decide" and 0.9 or above means "always lit", blue is the highlight threshold, and
	 * alpha selects which ramp row the material reads.
	 */
	private record Response(float occlusionMinimum, float occlusionMaximum,
		float specularMinimum, float specularMaximum, float highlight, float rampSelector) {
	}

	private static final Map<Category, Response> RESPONSES = new EnumMap<>(Map.of(
		Category.SKIN, new Response(0.42F, 0.56F, 0.02F, 0.08F, 0.20F, 0.30F),
		Category.CLOTH, new Response(0.40F, 0.56F, 0.06F, 0.18F, 0.35F, 0.70F),
		Category.HAIR, new Response(0.40F, 0.55F, 0.18F, 0.42F, 0.55F, 0.50F),
		Category.METAL, new Response(0.42F, 0.56F, 0.94F, 1.00F, 0.90F, 0.95F),
		Category.EYE, new Response(1.00F, 1.00F, 0.30F, 0.60F, 0.45F, 0.30F),
		Category.FACE, new Response(0.42F, 0.56F, 0.02F, 0.08F, 0.20F, 0.30F)));

	private static final int LIGHT_MAP_SIZE = 512;

	private final GltfModel model;
	private final ToonVrmData vrm;
	// A character's textures reach several thousand pixels a side, so the decoded copy
	// is large enough that holding every one of them is worse than decoding twice. The
	// sheets for a material are derived together, so remembering the last one is enough.
	private int cachedIndex = -1;
	private ToonImage cachedAlbedo;

	ToonMaterialSheets(GltfModel model, ToonVrmData vrm) {
		this.model = model;
		this.vrm = vrm;
	}

	/** The material's own colour, either its base colour texture or its flat factor. */
	ToonImage albedo(int materialIndex) {
		if (materialIndex == cachedIndex) {
			return cachedAlbedo;
		}
		// Released before the next is decoded so two never sit in memory at once.
		cachedAlbedo = null;
		cachedIndex = -1;
		cachedAlbedo = decodeAlbedo(materialIndex);
		cachedIndex = materialIndex;
		return cachedAlbedo;
	}

	private ToonImage decodeAlbedo(int materialIndex) {
		MaterialModel material = model.getMaterialModels().get(materialIndex);
		if (!(material instanceof MaterialModelV2 pbr)) {
			return ToonImage.solid(1.0F, 1.0F, 1.0F, 1.0F);
		}
		TextureModel texture = pbr.getBaseColorTexture();
		if (texture == null || texture.getImageModel() == null
			|| texture.getImageModel().getImageData() == null) {
			float[] factor = pbr.getBaseColorFactor();
			return ToonImage.solid(quantise(factor[0]), quantise(factor[1]), quantise(factor[2]),
				quantise(factor[3]));
		}
		try {
			return ToonImage.decode(texture.getImageModel().getImageData().duplicate());
		} catch (IOException exception) {
			throw new IllegalStateException(
				"material " + materialIndex + " has an undecodable base colour texture", exception);
		}
	}

	/**
	 * The LightMap. Detail comes from the material's own luminance so that authored
	 * creases and seams darken, and specular is weighted by how far a texel's normal
	 * leans off vertical so that upward facing surfaces do not all catch a highlight.
	 */
	ToonImage lightMap(int materialIndex, Category category) {
		Response response = RESPONSES.get(category);
		ToonRasterizer.Sample sample = ToonRasterizer.rasterize(model, vrm, materialIndex,
			LIGHT_MAP_SIZE, LIGHT_MAP_SIZE, true);
		ToonImage base = albedo(materialIndex).resampled(LIGHT_MAP_SIZE, LIGHT_MAP_SIZE);
		ToonImage image = new ToonImage(LIGHT_MAP_SIZE, LIGHT_MAP_SIZE);
		for (int y = 0; y < LIGHT_MAP_SIZE; y++) {
			for (int x = 0; x < LIGHT_MAP_SIZE; x++) {
				int texel = y * LIGHT_MAP_SIZE + x;
				float detail = clamp(0.55F + 0.45F * base.luminance(x, y));
				float orientation = clamp(0.55F + 0.45F * Math.abs(sample.normal(texel, 1)));
				float occlusion = response.occlusionMinimum() + (response.occlusionMaximum()
					- response.occlusionMinimum()) * detail;
				float specular = response.specularMinimum() + (response.specularMaximum()
					- response.specularMinimum()) * detail * orientation;
				float highlight = response.highlight() * (0.70F + 0.30F * detail);
				boolean covered = sample.covered(texel);
				image.set(x, y, 0, covered ? specular : response.specularMinimum());
				image.set(x, y, 1, covered ? occlusion : 1.0F);
				image.set(x, y, 2, covered ? highlight : response.highlight());
				image.set(x, y, 3, response.rampSelector());
			}
		}
		return image;
	}

	/**
	 * Outline colour: Genshin outlines are darkened, slightly saturated versions of the
	 * material's own colour rather than one flat black, which is what keeps a line
	 * readable against both a bright and a dark material.
	 */
	double[] outlineTint(int materialIndex) {
		ToonImage albedo = albedo(materialIndex);
		float[] mean = new float[3];
		float[] weights = new float[albedo.width() * albedo.height()];
		for (int y = 0; y < albedo.height(); y++) {
			for (int x = 0; x < albedo.width(); x++) {
				float weight = albedo.get(x, y, 3);
				weights[y * albedo.width() + x] = weight;
				for (int channel = 0; channel < 3; channel++) {
					mean[channel] += albedo.get(x, y, channel) * weight;
				}
			}
		}
		float divisor = Math.max(pairwiseSum(weights, 0, weights.length), 1.0E-6F);
		float grey = 0.0F;
		float[] luminance = {0.2126F, 0.7152F, 0.0722F};
		for (int channel = 0; channel < 3; channel++) {
			mean[channel] /= divisor;
			grey += mean[channel] * luminance[channel];
		}
		double[] tint = new double[4];
		for (int channel = 0; channel < 3; channel++) {
			float saturated = clamp(grey + (mean[channel] - grey) * 1.35F);
			tint[channel] = round(clamp(saturated * 0.34F + 0.03F));
		}
		tint[3] = 1.0;
		return tint;
	}

	/** The two sheets that drive facial shading. */
	record FaceSheets(ToonImage light, ToonImage shadow) {
	}

	/**
	 * The facial SDF pair. The shader lights a face pixel while the stored value is at
	 * least {@code (1 - dot(headForward, light)) / 2}, so what is stored is the
	 * horizontal angle the light may swing away from the front before this pixel turns
	 * dark. Deriving that from the head's own azimuth is what keeps the boundary
	 * sweeping around the cheek instead of cutting a Lambert wedge over the nose.
	 */
	FaceSheets faceSheets(int materialIndex) {
		ToonImage albedo = albedo(materialIndex);
		int width = albedo.width();
		int height = albedo.height();
		ToonRasterizer.Sample sample = ToonRasterizer.rasterize(model, vrm, materialIndex, width,
			height, false);
		// Everything below is expressed along the character's own forward and right
		// axes. Assuming either version's axes outright builds the map on the back of
		// the head, or mirrors it, for models authored to the other version.
		float forwardSign = vrm.headForward().z;
		float rightSign = vrm.headRight().x;

		boolean[] face = faceRegion(sample, albedo, forwardSign);
		float[] horizontal = new float[width * height];
		float[] vertical = new float[width * height];
		float[] depth = new float[width * height];
		int count = 0;
		for (int texel = 0; texel < face.length; texel++) {
			if (face[texel]) {
				horizontal[count] = sample.position(texel, 0);
				vertical[count] = sample.position(texel, 1);
				depth[count] = sample.position(texel, 2) * forwardSign;
				count++;
			}
		}
		float minimumX = percentile(horizontal, count, 2.0);
		float maximumX = percentile(horizontal, count, 98.0);
		float minimumY = percentile(vertical, count, 2.0);
		float maximumY = percentile(vertical, count, 98.0);
		float centerX = 0.5F * (minimumX + maximumX);
		float halfWidth = Math.max(0.5F * (maximumX - minimumX), 1.0E-6F);
		// The notional head sphere sits one radius behind the tip of the nose, which is
		// the frontmost face sample along the forward axis.
		float radius = Math.max(halfWidth, 1.0E-4F);
		float centerDepth = percentile(depth, count, 98.0) - radius;

		int texels = width * height;
		float[] centeredX = new float[texels];
		float[] centeredY = new float[texels];
		float[] luminance = new float[texels];
		float[] saturation = new float[texels];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int texel = y * width + x;
				float rightward = (sample.position(texel, 0) - centerX) * rightSign;
				centeredX[texel] = clamp(rightward / halfWidth, -1.0F, 1.0F);
				centeredY[texel] = clamp((sample.position(texel, 1) - minimumY)
					/ Math.max(maximumY - minimumY, 1.0E-6F), 0.0F, 1.0F);
				float red = albedo.get(x, y, 0);
				float green = albedo.get(x, y, 1);
				float blue = albedo.get(x, y, 2);
				luminance[texel] = 0.2126F * red + 0.7152F * green + 0.0722F * blue;
				saturation[texel] = Math.max(red, Math.max(green, blue))
					- Math.min(red, Math.min(green, blue));
			}
		}
		ToonFaceFeatures.Centers features = ToonFaceFeatures.compute(face, centeredX, centeredY,
			luminance, saturation);

		ToonImage light = new ToonImage(width, height);
		ToonImage shadow = new ToonImage(width, height);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int texel = y * width + x;
				float rightward = (sample.position(texel, 0) - centerX) * rightSign;
				float ahead = sample.position(texel, 2) * forwardSign - centerDepth;
				float sphereSine = clamp(rightward
					/ Math.max((float)Math.hypot(rightward, ahead), 1.0E-6F), -1.0F, 1.0F);
				float normalHorizontal = Math.max(
					(float)Math.hypot(sample.normal(texel, 0), sample.normal(texel, 2)), 1.0E-6F);
				float normalSine = clamp(sample.normal(texel, 0) * rightSign / normalHorizontal,
					-1.0F, 1.0F);
				// A little of the true normal keeps brow and cheek structure; too much
				// brings the nose triangle back.
				float blended = clamp(0.78F * sphereSine + 0.22F * normalSine, -1.0F, 1.0F);
				float blush = Math.max(
					gaussian(centeredX[texel], centeredY[texel], features.blushLeftX(),
						features.blushLeftY(), 0.18F, 0.08F),
					gaussian(centeredX[texel], centeredY[texel], features.blushRightX(),
						features.blushRightY(), 0.18F, 0.08F));
				float eyes = Math.max(
					gaussian(centeredX[texel], centeredY[texel], features.eyeLeftX(),
						features.eyeLeftY(), 0.14F, 0.06F),
					gaussian(centeredX[texel], centeredY[texel], features.eyeRightX(),
						features.eyeRightY(), 0.14F, 0.06F));
				boolean onFace = face[texel];
				light.set(x, y, 0, onFace ? clamp(0.5F + 0.5F * blended) : 0.0F);
				light.set(x, y, 1, onFace ? clamp(0.5F - 0.5F * blended) : 0.0F);
				light.set(x, y, 2, onFace ? clamp(blush * 0.85F) : 0.0F);
				light.set(x, y, 3, 1.0F);
				shadow.set(x, y, 0, 1.0F);
				shadow.set(x, y, 1, 1.0F);
				shadow.set(x, y, 2, 1.0F);
				shadow.set(x, y, 3, onFace ? clamp(eyes * 0.7F) : 1.0F);
			}
		}
		return new FaceSheets(light, shadow);
	}

	/**
	 * The texels that belong to the face. A face is regularly one region of a
	 * whole-body atlas, and normalising over every front-facing texel of such a
	 * material would place the cheeks somewhere around the waist. What the skeleton
	 * binds to the head decides, which is the same rule the specification's
	 * first-person "auto" mode uses to split head from body and does not depend on
	 * where the head joint happens to sit. Where the skeleton retains only a sliver it
	 * is not telling us where the head is, usually because the humanoid map is
	 * mis-authored, so the region is left alone.
	 */
	private static boolean[] faceRegion(ToonRasterizer.Sample sample, ToonImage albedo,
		float forwardSign) {
		boolean[] opaque = new boolean[sample.coverage().length];
		for (int y = 0; y < albedo.height(); y++) {
			for (int x = 0; x < albedo.width(); x++) {
				int texel = y * albedo.width() + x;
				opaque[texel] = sample.covered(texel) && albedo.get(x, y, 3) > 0.0F;
			}
		}
		boolean[] face = new boolean[opaque.length];
		int total = 0;
		int onHead = 0;
		for (int texel = 0; texel < face.length; texel++) {
			face[texel] = opaque[texel] && sample.normal(texel, 2) * forwardSign > 0.15F;
			total += face[texel] ? 1 : 0;
			onHead += face[texel] && sample.headWeights()[texel] > 0.5F ? 1 : 0;
		}
		if (onHead > 0.3F * Math.max(total, 1)) {
			for (int texel = 0; texel < face.length; texel++) {
				face[texel] &= sample.headWeights()[texel] > 0.5F;
			}
			total = onHead;
		}
		if (total > 0) {
			return face;
		}
		for (int texel = 0; texel < face.length; texel++) {
			total += opaque[texel] ? 1 : 0;
		}
		if (total == 0) {
			throw new IllegalArgumentException("the face material covers no opaque texels");
		}
		return opaque;
	}

	/**
	 * Pairwise summation over a whole sheet, which is how the offline tool's array
	 * library totals a contiguous buffer. Adding a few million texel weights one after
	 * another loses enough precision to move the outline colour it feeds, so the
	 * accumulation tree matters and not only the arithmetic.
	 */
	private static float pairwiseSum(float[] values, int start, int count) {
		if (count < 8) {
			float total = 0.0F;
			for (int index = 0; index < count; index++) {
				total += values[start + index];
			}
			return total;
		}
		if (count <= 128) {
			float[] partial = new float[8];
			System.arraycopy(values, start, partial, 0, 8);
			int index = 8;
			for (; index < count - count % 8; index += 8) {
				for (int lane = 0; lane < 8; lane++) {
					partial[lane] += values[start + index + lane];
				}
			}
			float total = ((partial[0] + partial[1]) + (partial[2] + partial[3]))
				+ ((partial[4] + partial[5]) + (partial[6] + partial[7]));
			for (; index < count; index++) {
				total += values[start + index];
			}
			return total;
		}
		int half = count / 2;
		half -= half % 8;
		return pairwiseSum(values, start, half) + pairwiseSum(values, start + half, count - half);
	}

	private static float gaussian(float x, float y, float centerX, float centerY, float radiusX,
		float radiusY) {
		float dx = (x - centerX) / radiusX;
		float dy = (y - centerY) / radiusY;
		return (float)Math.exp(-0.5 * (dx * dx + dy * dy));
	}

	/**
	 * Linear interpolation between the surrounding samples, matching how the offline
	 * tool takes percentiles. Trimming at the second and ninety-eighth percentile keeps
	 * a handful of stray texels from setting the extent of the whole face.
	 */
	private static float percentile(float[] values, int count, double percent) {
		float[] sorted = new float[count];
		System.arraycopy(values, 0, sorted, 0, count);
		java.util.Arrays.sort(sorted);
		double position = percent / 100.0 * (count - 1);
		int lower = (int)Math.floor(position);
		int upper = Math.min(lower + 1, count - 1);
		return (float)(sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower));
	}

	private static float quantise(float value) {
		return (float)Math.rint(clamp(value) * 255.0F) / 255.0F;
	}

	/**
	 * Four decimal places, rounded from the exact binary value with ties going to the
	 * even digit, which is how the offline tool rounds the colours it writes into the
	 * profile. Rounding the shortest decimal form instead would disagree on ties.
	 */
	private static double round(float value) {
		return new java.math.BigDecimal(value)
			.setScale(4, java.math.RoundingMode.HALF_EVEN).doubleValue();
	}

	private static float clamp(float value) {
		return clamp(value, 0.0F, 1.0F);
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.min(Math.max(value, minimum), maximum);
	}
}

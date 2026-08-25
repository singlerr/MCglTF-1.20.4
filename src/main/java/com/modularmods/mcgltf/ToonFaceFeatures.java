package com.modularmods.mcgltf;

/**
 * Derives where the blush and eye force-lit masks sit on a face atlas once the face
 * bounds come from geometry. The offline tool in the Celerant repository uses the same
 * rules so a model profiled in game matches one profiled on the command line.
 */
final class ToonFaceFeatures {
	record Centers(float eyeLeftX, float eyeLeftY, float eyeRightX, float eyeRightY,
		float blushLeftX, float blushLeftY, float blushRightX, float blushRightY) {
	}

	private ToonFaceFeatures() {
	}

	static Centers compute(boolean[] face, float[] centeredX, float[] centeredY,
		float[] luminance, float[] saturation) {
		float[] eyeWeight = new float[face.length];
		float[] cheekWeight = new float[face.length];
		for (int texel = 0; texel < face.length; texel++) {
			if (!face[texel]) {
				continue;
			}
			float y = centeredY[texel];
			float lum = luminance[texel];
			float sat = saturation[texel];
			if (y > 0.35F && y < 0.65F && lum < 0.72F && sat > 0.08F) {
				eyeWeight[texel] = Math.max(0.01F, 0.8F - lum + sat);
			}
			if (Math.abs(centeredX[texel]) > 0.18F && y > 0.22F && y < 0.48F && sat < 0.28F
				&& lum > 0.42F) {
				cheekWeight[texel] = Math.abs(centeredX[texel])
					* clamp(0.45F - Math.abs(y - 0.33F), 0.05F, 0.45F);
			}
		}
		float[] eyeLeft = weightedCenter(face, centeredX, centeredY, eyeWeight, true);
		float[] eyeRight = weightedCenter(face, centeredX, centeredY, eyeWeight, false);
		float[] blushLeft = weightedCenter(face, centeredX, centeredY, cheekWeight, true);
		float[] blushRight = weightedCenter(face, centeredX, centeredY, cheekWeight, false);
		return new Centers(
			center(eyeLeft, -0.28F, 0.52F, 0),
			center(eyeLeft, -0.28F, 0.52F, 1),
			center(eyeRight, 0.28F, 0.52F, 0),
			center(eyeRight, 0.28F, 0.52F, 1),
			center(blushLeft, -0.38F, 0.34F, 0),
			center(blushLeft, -0.38F, 0.34F, 1),
			center(blushRight, 0.38F, 0.34F, 0),
			center(blushRight, 0.38F, 0.34F, 1));
	}

	private static float[] weightedCenter(boolean[] face, float[] centeredX, float[] centeredY,
		float[] weights, boolean negativeSide) {
		double sumWeight = 0.0D;
		double sumX = 0.0D;
		double sumY = 0.0D;
		for (int texel = 0; texel < face.length; texel++) {
			if (!face[texel] || weights[texel] <= 0.0F) {
				continue;
			}
			if (negativeSide && centeredX[texel] >= 0.0F || !negativeSide && centeredX[texel] <= 0.0F) {
				continue;
			}
			double weight = weights[texel];
			sumWeight += weight;
			sumX += centeredX[texel] * weight;
			sumY += centeredY[texel] * weight;
		}
		if (sumWeight <= 0.0D) {
			return null;
		}
		return new float[] {(float)(sumX / sumWeight), (float)(sumY / sumWeight)};
	}

	private static float center(float[] value, float fallbackX, float fallbackY, int component) {
		return value == null ? (component == 0 ? fallbackX : fallbackY) : value[component];
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}

package com.modularmods.mcgltf;

/**
 * The tone sheets a Genshin style material reads instead of computing its shading:
 * the shadow ramps and the metal matcap. Neither depends on the model, so both are the
 * same for every character and are authored here rather than asked of the user.
 */
final class ToonToneSheets {
	/**
	 * A ramp row: the deep shadow colour, the subsurface mid tone, where the deep to
	 * mid transition begins, and where it reaches the mid tone.
	 */
	private record Band(float[] deep, float[] mid, float start, float middle) {
	}

	/**
	 * Rows are indexed the way the reference shader indexes them from the LightMap
	 * alpha: soft common cloth, skin, hair, metal, then remaining matte surfaces.
	 */
	private static final Band[] BODY_BANDS = {
		band(0.60F, 0.53F, 0.58F, 0.86F, 0.75F, 0.75F, 0.50F, 0.86F),
		band(0.70F, 0.49F, 0.49F, 0.94F, 0.73F, 0.69F, 0.44F, 0.88F),
		band(0.53F, 0.51F, 0.65F, 0.80F, 0.76F, 0.89F, 0.52F, 0.87F),
		band(0.43F, 0.46F, 0.58F, 0.72F, 0.76F, 0.90F, 0.62F, 0.90F),
		band(0.58F, 0.56F, 0.61F, 0.84F, 0.80F, 0.83F, 0.52F, 0.87F)};
	private static final Band[] HAIR_BANDS = {
		band(0.52F, 0.48F, 0.60F, 0.80F, 0.74F, 0.86F, 0.50F, 0.86F),
		band(0.62F, 0.47F, 0.52F, 0.90F, 0.72F, 0.72F, 0.46F, 0.88F),
		band(0.46F, 0.45F, 0.62F, 0.76F, 0.74F, 0.92F, 0.54F, 0.88F),
		band(0.40F, 0.43F, 0.58F, 0.70F, 0.74F, 0.90F, 0.62F, 0.90F),
		band(0.50F, 0.49F, 0.58F, 0.80F, 0.77F, 0.84F, 0.52F, 0.87F)};
	private static final float[] NIGHT_TINT = {0.74F, 0.80F, 1.00F};

	private ToonToneSheets() {
	}

	static ToonImage bodyRamp() {
		return ramp(BODY_BANDS);
	}

	static ToonImage hairRamp() {
		return ramp(HAIR_BANDS);
	}

	/**
	 * A 256x20 ramp laid out like the official Genshin shadow ramp sheets: five
	 * material bands per daylight state, authored for the bottom-up V axis those sheets
	 * use, which is why the daylight bands sit in the top half of the image.
	 */
	private static ToonImage ramp(Band[] bands) {
		int width = 256;
		ToonImage image = new ToonImage(width, 20);
		for (int index = 0; index < bands.length; index++) {
			int top = 8 - 2 * index;
			for (int row = 0; row < 2; row++) {
				writeBand(image, bands[index], top + row, false);
				writeBand(image, bands[index], top + row + 10, true);
			}
		}
		for (int y = 0; y < image.height(); y++) {
			for (int x = 0; x < width; x++) {
				image.set(x, y, 3, 1.0F);
			}
		}
		return image;
	}

	/** One ramp row: darkest at the left, lit at the right, transition pushed right. */
	private static void writeBand(ToonImage image, Band band, int row, boolean night) {
		int last = image.width() - 1;
		for (int x = 0; x <= last; x++) {
			float position = x == last ? 1.0F : (float)(x * (1.0 / last));
			float deepToMid = smoothstep(band.start(), band.middle(), position);
			float midToLit = smoothstep(band.middle(), 1.0F, position);
			for (int channel = 0; channel < 3; channel++) {
				float shadow = band.deep()[channel]
					+ (band.mid()[channel] - band.deep()[channel]) * deepToMid;
				if (night) {
					shadow = Math.min(Math.max(shadow * NIGHT_TINT[channel] * 0.88F, 0.0F), 1.0F);
				}
				// The lit end stays neutral: the shader hard-switches to white once the
				// shadow term passes the range maximum, and a tinted right edge would
				// show up as a seam right at that switch.
				image.set(x, row, channel, shadow + (1.0F - shadow) * midToLit);
			}
		}
	}

	/**
	 * A neutral metal matcap: a lit sphere brightening towards its centre, with the
	 * area outside the sphere left transparent so it contributes nothing.
	 */
	static ToonImage matcap() {
		int size = 256;
		ToonImage image = new ToonImage(size, size);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double u = x / (double)(size - 1) * 2.0 - 1.0;
				double v = y / (double)(size - 1) * 2.0 - 1.0;
				double radius = Math.sqrt(u * u + v * v);
				if (radius > 1.0) {
					continue;
				}
				double highlight = Math.pow(Math.min(Math.max(1.0 - radius, 0.0), 1.0), 3.0);
				image.set(x, y, 0, (float)Math.rint(180.0 + 75.0 * highlight) / 255.0F);
				image.set(x, y, 1, (float)Math.rint(185.0 + 70.0 * highlight) / 255.0F);
				image.set(x, y, 2, (float)Math.rint(200.0 + 55.0 * highlight) / 255.0F);
				image.set(x, y, 3, 1.0F);
			}
		}
		return image;
	}

	static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.min(Math.max((x - edge0) / Math.max(edge1 - edge0, 1.0E-6F), 0.0F), 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static Band band(float deepRed, float deepGreen, float deepBlue, float midRed,
		float midGreen, float midBlue, float start, float middle) {
		return new Band(new float[] {deepRed, deepGreen, deepBlue},
			new float[] {midRed, midGreen, midBlue}, start, middle);
	}
}

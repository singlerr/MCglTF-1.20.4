package com.modularmods.mcgltf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.util.ARGB;

/**
 * A linear RGBA raster used while deriving toon data, kept in floats so that the
 * synthesis steps can be reasoned about and tested without a graphics context.
 *
 * <p>Channels are ordered red, green, blue, alpha, which is the order the sheets are
 * authored and read in. {@link NativeImage} packs them the other way round, so that
 * conversion happens here once rather than in every step that touches a pixel.
 */
final class ToonImage {
	private final int width;
	private final int height;
	private final float[] channels;

	ToonImage(int width, int height) {
		this.width = width;
		this.height = height;
		this.channels = new float[width * height * 4];
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	float get(int x, int y, int channel) {
		return channels[(y * width + x) * 4 + channel];
	}

	void set(int x, int y, int channel, float value) {
		channels[(y * width + x) * 4 + channel] = value;
	}

	void fill(float red, float green, float blue, float alpha) {
		for (int texel = 0; texel < width * height; texel++) {
			channels[texel * 4] = red;
			channels[texel * 4 + 1] = green;
			channels[texel * 4 + 2] = blue;
			channels[texel * 4 + 3] = alpha;
		}
	}

	/** Rec. 709 luminance, which is what the reference weights detail by. */
	float luminance(int x, int y) {
		return 0.2126F * get(x, y, 0) + 0.7152F * get(x, y, 1) + 0.0722F * get(x, y, 2);
	}

	static ToonImage solid(float red, float green, float blue, float alpha) {
		ToonImage image = new ToonImage(1, 1);
		image.fill(red, green, blue, alpha);
		return image;
	}

	static ToonImage decode(ByteBuffer data) throws IOException {
		try (NativeImage decoded = NativeImage.read(data)) {
			ToonImage image = new ToonImage(decoded.getWidth(), decoded.getHeight());
			for (int y = 0; y < image.height; y++) {
				for (int x = 0; x < image.width; x++) {
					int pixel = decoded.getPixel(x, y);
					image.set(x, y, 0, ARGB.red(pixel) / 255.0F);
					image.set(x, y, 1, ARGB.green(pixel) / 255.0F);
					image.set(x, y, 2, ARGB.blue(pixel) / 255.0F);
					image.set(x, y, 3, ARGB.alpha(pixel) / 255.0F);
				}
			}
			return image;
		}
	}

	void write(Path path) throws IOException {
		try (NativeImage image = new NativeImage(width, height, false)) {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setPixel(x, y, ARGB.color(channel(x, y, 3), channel(x, y, 0),
						channel(x, y, 1), channel(x, y, 2)));
				}
			}
			image.writeToFile(path);
		}
	}

	/**
	 * Quantises with ties going to the even value, which is how the offline tool's
	 * rounding behaves. Half way values do occur where a channel is authored as a
	 * constant, and rounding them the other way would show up as an off by one
	 * difference between a model profiled in game and the same model profiled offline.
	 */
	private int channel(int x, int y, int channel) {
		return quantise(get(x, y, channel));
	}

	/**
	 * Lanczos resample through the same eight bit pipeline the offline tool's resampler
	 * uses: colour premultiplied by alpha, one pass per axis, fixed point coefficients,
	 * and a quantisation between the passes.
	 *
	 * <p>Premultiplying is what stops colour in fully transparent regions of an atlas
	 * from bleeding into a material's detail, and the quantisation steps are observable
	 * in the result, so both are reproduced rather than approximated with a plain float
	 * filter. A resample to the same size is a copy, as it is there.
	 */
	ToonImage resampled(int targetWidth, int targetHeight) {
		if (targetWidth == width && targetHeight == height) {
			return this;
		}
		int[] pixels = premultiplied();
		int stageWidth = width;
		if (targetWidth != width) {
			pixels = resampleAxis(pixels, width, height, targetWidth, true);
			stageWidth = targetWidth;
		}
		if (targetHeight != height) {
			pixels = resampleAxis(pixels, stageWidth, height, targetHeight, false);
		}
		return unpremultiplied(pixels, targetWidth, targetHeight);
	}

	/** Fixed point fraction bits, sized so a full tap sum cannot overflow. */
	private static final int PRECISION_BITS = 22;

	private int[] premultiplied() {
		int[] pixels = new int[width * height * 4];
		for (int texel = 0; texel < width * height; texel++) {
			int alpha = quantise(channels[texel * 4 + 3]);
			for (int channel = 0; channel < 3; channel++) {
				int scaled = quantise(channels[texel * 4 + channel]) * alpha + 128;
				pixels[texel * 4 + channel] = ((scaled >> 8) + scaled) >> 8;
			}
			pixels[texel * 4 + 3] = alpha;
		}
		return pixels;
	}

	private static ToonImage unpremultiplied(int[] pixels, int width, int height) {
		ToonImage image = new ToonImage(width, height);
		for (int texel = 0; texel < width * height; texel++) {
			int alpha = pixels[texel * 4 + 3];
			for (int channel = 0; channel < 3; channel++) {
				int value = pixels[texel * 4 + channel];
				image.channels[texel * 4 + channel] = (alpha == 0 || alpha == 255 ? value
					: Math.min(Math.max(255 * value / alpha, 0), 255)) / 255.0F;
			}
			image.channels[texel * 4 + 3] = alpha / 255.0F;
		}
		return image;
	}

	private static int[] resampleAxis(int[] source, int sourceWidth, int sourceHeight,
		int targetSize, boolean horizontal) {
		int targetWidth = horizontal ? targetSize : sourceWidth;
		int targetHeight = horizontal ? sourceHeight : targetSize;
		Taps taps = taps(horizontal ? sourceWidth : sourceHeight, targetSize);
		int rounding = 1 << (PRECISION_BITS - 1);
		int[] target = new int[targetWidth * targetHeight * 4];
		for (int y = 0; y < targetHeight; y++) {
			for (int x = 0; x < targetWidth; x++) {
				int along = horizontal ? x : y;
				for (int channel = 0; channel < 4; channel++) {
					int total = rounding;
					for (int tap = 0; tap < taps.length[along]; tap++) {
						int sourceX = horizontal ? taps.start[along] + tap : x;
						int sourceY = horizontal ? y : taps.start[along] + tap;
						total += source[(sourceY * sourceWidth + sourceX) * 4 + channel]
							* taps.coefficients[along * taps.max + tap];
					}
					target[(y * targetWidth + x) * 4 + channel] = Math.min(
						Math.max(total >> PRECISION_BITS, 0), 255);
				}
			}
		}
		return target;
	}

	private static int quantise(float value) {
		return (int)Math.rint(Math.min(Math.max(value, 0.0F), 1.0F) * 255.0F);
	}

	private record Taps(int[] start, int[] length, int[] coefficients, int max) {
	}

	private static Taps taps(int sourceSize, int targetSize) {
		double scale = (double)sourceSize / targetSize;
		// Enlarging keeps the filter at its own width; reducing widens it to the source
		// spacing, which is what stops a downscale from aliasing.
		double filterScale = Math.max(scale, 1.0);
		double support = 3.0 * filterScale;
		int max = (int)Math.ceil(support) * 2 + 1;
		Taps taps = new Taps(new int[targetSize], new int[targetSize],
			new int[targetSize * max], max);
		double[] weights = new double[max];
		for (int target = 0; target < targetSize; target++) {
			double center = (target + 0.5) * scale;
			int minimum = Math.max((int)(center - support + 0.5), 0);
			int maximum = Math.min((int)(center + support + 0.5), sourceSize);
			double total = 0.0;
			for (int source = minimum; source < maximum; source++) {
				double weight = lanczos((source - center + 0.5) / filterScale);
				weights[source - minimum] = weight;
				total += weight;
			}
			for (int tap = 0; tap < maximum - minimum; tap++) {
				double weight = total == 0.0 ? weights[tap] : weights[tap] / total;
				taps.coefficients[target * max + tap] = (int)(weight < 0.0
					? weight * (1 << PRECISION_BITS) - 0.5 : weight * (1 << PRECISION_BITS) + 0.5);
			}
			taps.start[target] = minimum;
			taps.length[target] = maximum - minimum;
		}
		return taps;
	}

	private static double lanczos(double x) {
		return x <= -3.0 || x >= 3.0 ? 0.0 : sinc(x) * sinc(x / 3.0);
	}

	private static double sinc(double x) {
		if (x == 0.0) {
			return 1.0;
		}
		double scaled = Math.PI * x;
		return Math.sin(scaled) / scaled;
	}
}

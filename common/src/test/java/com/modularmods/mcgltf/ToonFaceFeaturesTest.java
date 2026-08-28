package com.modularmods.mcgltf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToonFaceFeaturesTest {
	@Test
	void placesEyesOnAuthoredDarkIslands() {
		boolean[] face = gridFace();
		float[] centeredX = gridCenteredX();
		float[] centeredY = gridCenteredY();
		float[] luminance = flat(0.68F);
		float[] saturation = flat(0.05F);
		paintEye(luminance, saturation, centeredX, centeredY, -0.52F, 0.50F);
		paintEye(luminance, saturation, centeredX, centeredY, 0.52F, 0.50F);

		ToonFaceFeatures.Centers centers = ToonFaceFeatures.compute(face, centeredX, centeredY,
			luminance, saturation);
		assertEquals(-0.52F, centers.eyeLeftX(), 0.08F);
		assertEquals(0.52F, centers.eyeRightX(), 0.08F);
		assertEquals(0.50F, centers.eyeLeftY(), 0.08F);
		assertEquals(0.50F, centers.eyeRightY(), 0.08F);
	}

	@Test
	void fallsBackWhenTheFaceHasNoAuthoredEyeIslands() {
		boolean[] face = gridFace();
		ToonFaceFeatures.Centers centers = ToonFaceFeatures.compute(face, gridCenteredX(),
			gridCenteredY(), flat(0.75F), flat(0.35F));
		assertEquals(-0.28F, centers.eyeLeftX(), 0.0001F);
		assertEquals(0.28F, centers.eyeRightX(), 0.0001F);
		assertEquals(-0.38F, centers.blushLeftX(), 0.0001F);
		assertEquals(0.38F, centers.blushRightX(), 0.0001F);
	}

	@Test
	void placesBlushOnLowerOuterCheekGeometry() {
		boolean[] face = gridFace();
		float[] centeredX = gridCenteredX();
		float[] centeredY = gridCenteredY();
		float[] luminance = flat(0.62F);
		float[] saturation = flat(0.08F);
		for (int texel = 0; texel < face.length; texel++) {
			if (Math.abs(centeredX[texel]) > 0.45F && centeredY[texel] > 0.28F && centeredY[texel] < 0.40F) {
				luminance[texel] = 0.58F;
				saturation[texel] = 0.06F;
			}
		}

		ToonFaceFeatures.Centers centers = ToonFaceFeatures.compute(face, centeredX, centeredY,
			luminance, saturation);
		assertTrue(centers.blushLeftX() < -0.30F);
		assertTrue(centers.blushRightX() > 0.30F);
		assertTrue(centers.blushLeftY() > 0.25F && centers.blushLeftY() < 0.42F);
	}

	private static boolean[] gridFace() {
		boolean[] face = new boolean[100];
		for (int index = 0; index < face.length; index++) {
			face[index] = true;
		}
		return face;
	}

	private static float[] gridCenteredX() {
		float[] centeredX = new float[100];
		for (int index = 0; index < centeredX.length; index++) {
			centeredX[index] = (index % 10) / 9.0F * 2.0F - 1.0F;
		}
		return centeredX;
	}

	private static float[] gridCenteredY() {
		float[] centeredY = new float[100];
		for (int index = 0; index < centeredY.length; index++) {
			centeredY[index] = (index / 10) / 9.0F;
		}
		return centeredY;
	}

	private static float[] flat(float value) {
		float[] values = new float[100];
		for (int index = 0; index < values.length; index++) {
			values[index] = value;
		}
		return values;
	}

	private static void paintEye(float[] luminance, float[] saturation, float[] centeredX,
		float[] centeredY, float eyeX, float eyeY) {
		for (int texel = 0; texel < luminance.length; texel++) {
			if (Math.abs(centeredX[texel] - eyeX) < 0.12F && Math.abs(centeredY[texel] - eyeY) < 0.10F) {
				luminance[texel] = 0.20F;
				saturation[texel] = 0.30F;
			}
		}
	}
}

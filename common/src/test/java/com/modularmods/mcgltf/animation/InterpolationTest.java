package com.modularmods.mcgltf.animation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InterpolationTest {

	@Test
	void interpolationModesCoverBoundsAndMidpoints() {
		float[] linearOutput = new float[1];
		LinearInterpolatedChannel linear = new LinearInterpolatedChannel(new float[] {0.0F, 2.0F},
			new float[][] {{2.0F}, {6.0F}}) {
			@Override
			protected float[] getListener() {
				return linearOutput;
			}
		};
		linear.update(1.0F);
		assertArrayEquals(new float[] {4.0F}, linearOutput, 1.0E-6F);
		linear.update(-1.0F);
		assertArrayEquals(new float[] {2.0F}, linearOutput, 1.0E-6F);

		float[] stepOutput = new float[1];
		StepInterpolatedChannel step = new StepInterpolatedChannel(new float[] {0.0F, 2.0F},
			new float[][] {{2.0F}, {6.0F}}) {
			@Override
			protected float[] getListener() {
				return stepOutput;
			}
		};
		step.update(1.0F);
		assertArrayEquals(new float[] {2.0F}, stepOutput, 1.0E-6F);

		float[] quaternion = new float[4];
		SphericalLinearInterpolatedChannel slerp = new SphericalLinearInterpolatedChannel(
			new float[] {0.0F, 1.0F}, new float[][] {{0.0F, 0.0F, 0.0F, 1.0F}, {0.0F, 1.0F, 0.0F, 0.0F}}) {
			@Override
			protected float[] getListener() {
				return quaternion;
			}
		};
		slerp.update(0.5F);
		assertEquals(1.0F, quaternion[1] * quaternion[1] + quaternion[3] * quaternion[3], 1.0E-5F);

		float[] cubicOutput = new float[1];
		CubicSplineInterpolatedChannel cubic = new CubicSplineInterpolatedChannel(new float[] {0.0F, 1.0F},
			new float[][][] {{{0.0F}, {0.0F}, {1.0F}}, {{1.0F}, {1.0F}, {0.0F}}}) {
			@Override
			protected float[] getListener() {
				return cubicOutput;
			}
		};
		cubic.update(0.5F);
		assertEquals(0.5F, cubicOutput[0], 1.0E-6F);
	}

	@Test
	void binarySearchSegmentSelectionIsStable() {
		float[] keys = {0.0F, 1.0F, 2.0F};
		assertEquals(0, InterpolatedChannel.computeIndex(-1.0F, keys));
		assertEquals(0, InterpolatedChannel.computeIndex(0.5F, keys));
		assertEquals(1, InterpolatedChannel.computeIndex(1.5F, keys));
		assertEquals(2, InterpolatedChannel.computeIndex(3.0F, keys));
	}
}

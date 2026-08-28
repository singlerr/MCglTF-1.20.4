package com.modularmods.mcgltf;

import org.joml.Vector3f;

final class ToonShaderVertexPacking {
	private ToonShaderVertexPacking() {
	}

	static int packTangent(Vector3f tangent, float handedness) {
		float[] oct = octEncode(tangent);
		int x = Math.round((oct[0] * 0.5F + 0.5F) * 32767.0F) & 0x7FFF;
		int y = Math.round((oct[1] * 0.5F + 0.5F) * 32767.0F) & 0x7FFF;
		return x | (handedness < 0.0F ? 0x8000 : 0) | y << 16;
	}

	static int packSmoothNormal(Vector3f normal) {
		float[] oct = octEncode(normal);
		int x = Math.round(oct[0] * 32767.0F) & 0xFFFF;
		int y = Math.round(oct[1] * 32767.0F) & 0xFFFF;
		return x | y << 16;
	}

	private static float[] octEncode(Vector3f vector) {
		float inverseLength = 1.0F / (Math.abs(vector.x) + Math.abs(vector.y) + Math.abs(vector.z));
		float x = vector.x * inverseLength;
		float y = vector.y * inverseLength;
		if (vector.z < 0.0F) {
			float oldX = x;
			x = (1.0F - Math.abs(y)) * Math.copySign(1.0F, oldX);
			y = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, y);
		}
		return new float[] {x, y};
	}
}

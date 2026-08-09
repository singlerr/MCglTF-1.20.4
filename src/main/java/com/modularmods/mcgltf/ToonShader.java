package com.modularmods.mcgltf;

import org.joml.Matrix4fc;

/** Entry points for the optional ToonShader renderer. */
public final class ToonShader {
	private ToonShader() {
	}

	public static void renderFrame() {
		ToonShaderRenderer.render();
	}

	public static void captureProjection(Matrix4fc projection) {
		ToonShaderRenderer.captureProjection(projection);
	}

	public static long getLastRenderNanos() {
		return ToonShaderRenderer.lastRenderNanos();
	}
}

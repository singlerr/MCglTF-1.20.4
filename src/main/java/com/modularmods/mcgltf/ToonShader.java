package com.modularmods.mcgltf;

import org.joml.Matrix4fc;

/** Entry points for the optional ToonShader renderer. */
public final class ToonShader {
	private static volatile boolean bloomEnabled = true;

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

	public static void setBloomEnabled(boolean enabled) {
		bloomEnabled = enabled;
	}

	public static boolean isBloomEnabled() {
		return bloomEnabled;
	}
}

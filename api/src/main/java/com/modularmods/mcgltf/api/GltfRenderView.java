package com.modularmods.mcgltf.api;

/**
 * Opaque view filter for {@link GltfRenderable#submit}. Only {@link #FULL} is exposed through the
 * public API; advanced node filtering remains an internal concern.
 */
public final class GltfRenderView {

	public static final GltfRenderView FULL = new GltfRenderView();

	private GltfRenderView() {
	}
}
